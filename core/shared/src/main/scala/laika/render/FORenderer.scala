/*
 * Copyright 2012-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package laika.render

import cats.implicits._
import cats.data.NonEmptySet
import laika.ast.{InternalTarget, Styles, _}
import laika.render.FOFormatter._

/** Default renderer implementation for the XSL-FO output format.
  *
  * @author Jens Halm
  */
object FORenderer extends ((FOFormatter, Element) => String) {

  private val formats: NonEmptySet[String] = NonEmptySet.of("pdf", "fo", "xslfo", "xsl-fo")
  
  def apply (fmt: FOFormatter, element: Element): String = {

    def noneIfDefault [T](actual: T, default: T): Option[String] = if (actual == default) None else Some(actual.toString)

    def renderTable (table: Table): String = {
      if (table.caption.content.nonEmpty) {
        // FOP does not support fo:table-caption
        fmt.child(TitledBlock(table.caption.content, List(table.copy(caption = Caption()))))
      }
      else {
        val children = table.columns.content ++ (List(table.head, table.body) filterNot (_.content.isEmpty))
        fmt.indentedElement("fo:table", table, children)
      }
    }

    object WithFallback {
      def unapply (value: Element): Option[Element] = value match {
        case f: Fallback => Some(f.fallback)
        case _ => None
      }
    }

    def renderBlockContainer (con: BlockContainer): String = {

      def quotedBlockContent (content: Seq[Block], attr: Seq[Span]): Seq[Block] =
        if (attr.isEmpty) content
        else content :+ SpanSequence(attr, Style.attribution)

      def figureContent (img: Span, caption: Seq[Span], legend: Seq[Block]): List[Block] =
        List(Paragraph(img), SpanSequence(caption, Style.caption), BlockSequence(legend, Style.legend))

      def enumLabel (format: EnumFormat, num: Int): String = {
        import EnumType._
        val pos = format.enumType match {
          case Arabic => num.toString
          case LowerAlpha => ('a' + num - 1).toChar.toString
          case UpperAlpha => ('A' + num - 1).toChar.toString
          case LowerRoman => RomanNumerals.intToRoman(num).toLowerCase
          case UpperRoman => RomanNumerals.intToRoman(num).toUpperCase
        }
        format.prefix + pos + format.suffix
      }

      def bulletLabel (format: BulletFormat): Span = format match {
        case StringBullet(_) => RawContent(NonEmptySet.one("fo"), "&#x2022;")
        case other           => Text(other.toString)
      }

      def replaceSpanSequences (content: Seq[Block]): Seq[Block] = content map {
        case sc: SpanSequence => Paragraph(sc.content, sc.options)
        case other => other
      }

      con match {
        case RootElement(content, _)            => fmt.childPerLine(content)
        case EmbeddedRoot(content, indent, _)   => fmt.withMinIndentation(indent)(_.childPerLine(content))
        case Section(header, content,_)         => fmt.childPerLine(header +: content)
        case e @ TitledBlock(title, content, _) => fmt.blockContainer(e, SpanSequence(title, Style.title) +: content)
        case e @ QuotedBlock(content,attr,_)    => fmt.blockContainer(e, quotedBlockContent(content,attr))

        case e @ BulletListItem(content,format,_)   => fmt.listItem(e, List(bulletLabel(format)), content)
        case e @ EnumListItem(content,format,num,_) => fmt.listItem(e, List(Text(enumLabel(format,num))), content)
        case e @ DefinitionListItem(term,defn,_)    => fmt.listItem(e, term, defn)
        case e @ ListItemBody(content,_)            => fmt.listItemBody(e, replaceSpanSequences(content))

        case e @ Figure(img,caption,legend,_)   => fmt.blockContainer(e, figureContent(img,caption,legend))

        case e @ FootnoteBody(content,_)        => fmt.indentedElement("fo:footnote-body", e, content)
        case _: Footnote                        => "" // rendered in link position
        case _: Citation                        => "" // rendered in link position

        case WithFallback(fallback)         => fmt.child(fallback)
        case c: Customizable                => c match {
          case BlockSequence(Seq(SpanSequence(Seq(img: Image), optSpan)), optBlock) => 
            fmt.child(SpanSequence(Seq(img.mergeOptions(optSpan + optBlock)), Styles("align-center", "default-space")))
          case BlockSequence(content, NoOpt) => fmt.childPerLine(content) // this case could be standalone above, but triggers a compiler bug then
          case unknown                      => fmt.blockContainer(unknown, unknown.content)
        }
        case unknown                        => fmt.blockContainer(unknown, unknown.content)
      }
    }

    def renderSpanContainer (con: SpanContainer): String = {
      def codeStyles (language: String) = if (language.isEmpty) NoOpt else Styles(language)

      con match {

        case Paragraph(Seq(img: Image), _)    => fmt.child(SpanSequence(Seq(img), Styles("align-center", "default-space")))
        case e @ Paragraph(content,_)         => fmt.block(e, content)
        case e @ ParsedLiteralBlock(content,_)=> fmt.blockWithWS(e, content)
        case e @ CodeBlock(lang,content,_)    => fmt.blockWithWS(e.copy(options=e.options + codeStyles(lang)),content)
        case e @ Header(level, content,_)     => fmt.block(e.copy(options=e.options + Style.level(level)), content, "keep-with-next"->"always")
        case e @ Title(content,_)             => fmt.block(e.copy(options=e.options), content, "keep-with-next"->"always")  

        case e @ Emphasized(content,_)        => fmt.inline(e, content)
        case e @ Strong(content,_)            => fmt.inline(e, content)
        case e @ Deleted(content,_)           => fmt.inline(e, content)
        case e @ Inserted(content,_)          => fmt.inline(e, content)
        case e @ InlineCode(lang,content,_)   => fmt.inline(e.copy(options=e.options + codeStyles(lang)),content)
        case e @ Line(content,_)              => fmt.block(e, content)

        case e @ SpanLink(content, ExternalTarget(url), _, _) => fmt.externalLink(e, url, content)
        case e @ SpanLink(content, ResolvedInternalTarget(_, _, Some(url)), _, _) => fmt.externalLink(e, url, content)
        case e @ SpanLink(content, it: InternalTarget, _, _) =>
          fmt.internalLink(e, fmt.buildId(it.relativeTo(fmt.path).absolutePath), content)

        case WithFallback(fallback)         => fmt.child(fallback)
        case c: Customizable                => c match {
          case SpanSequence(content, NoOpt) => fmt.children(content) // this case could be standalone above, but triggers a compiler bug then
          case CodeSpanSequence(content, NoOpt) => fmt.children(content)
          case unknown: Block               => fmt.block(unknown, unknown.content) // TODO - needs to be inline if parent is not a block container
          case unknown                      => fmt.inline(unknown, unknown.content)
        }
        case unknown                        => fmt.inline(unknown, unknown.content)
      }
    }

    def renderTemplateSpanContainer (con: TemplateSpanContainer): String = {
      con match {
        case TemplateRoot(content, NoOpt)         => fmt.children(content)
        case TemplateSpanSequence(content, NoOpt) => fmt.children(content)
        case unknown                              => fmt.inline(unknown, unknown.content)
      }
    }

    def renderListContainer (con: ListContainer): String = con match {
      case e @ EnumList(content,_,_,_)   => fmt.listBlock(e, content)
      case e @ BulletList(content,_,_)   => fmt.listBlock(e, content)
      case e @ DefinitionList(content,_) => fmt.listBlock(e, content)
      case e: NavigationList             => if (e.options.styles.contains("bookmark")) fmt.bookmarkTree(e) else fmt.childPerLine(e.content)

      case WithFallback(fallback)      => fmt.child(fallback)
      case c: Customizable             => fmt.listBlock(c, c.content)
      case unknown                     => fmt.listBlock(unknown, unknown.content)
    }

    def renderTextContainer (con: TextContainer): String = con match {
      case e @ Text(content,_)           => fmt.text(e, content)
      case e @ TemplateString(content,_) => fmt.rawText(e, content)
      case e @ RawContent(f, content, _) => if (f.intersect(formats).nonEmpty) fmt.rawText(e, content) else ""
      case e @ CodeSpan(content, categories, _) => fmt.textWithWS(e.mergeOptions(Styles(categories.map(_.name).toSeq:_*)), content)
      case e @ Literal(content,_)        => fmt.textWithWS(e, content)
      case e @ LiteralBlock(content,_)   => fmt.textBlockWithWS(e, content)
      case e: BookmarkTitle              => fmt.bookmarkTitle(e)
      case Comment(content,_)            => fmt.comment(content)

      case WithFallback(fallback)      => fmt.child(fallback)
      case c: Customizable             => fmt.text(c, c.content)
      case unknown                     => fmt.text(unknown.content)
    }

    def renderChoices (name: String, choices: Seq[Choice], options: Options): String = {
      val content = choices.flatMap { choice => Paragraph(Strong(Text(choice.label))) +: choice.content }
      fmt.child(BlockSequence(content, options))
    }

    def renderSimpleBlock (block: Block): String = block match {
      case e: Preamble                  => renderPreamble(e)  
      case e @ ListItemLabel(content,_) => fmt.listItemLabel(e, content)
      case e: Rule                      => fmt.rawElement("fo:block", e, fmt.textElement("fo:leader", e, "", "leader-pattern"->"rule"))
      case Selection(name, choices, opt) => renderChoices(name, choices, opt)
      case e: InternalLinkTarget        => fmt.internalLinkTarget(e)
      case e: PageBreak                 => fmt.block(e)
      case e @ LineBlock(content,_)     => fmt.blockContainer(e, content)
      case TargetFormat(f,e,_) if f.intersect(formats).nonEmpty => fmt.child(e)

      case WithFallback(fallback)       => fmt.child(fallback)
      case _                            => ""
    }

    def renderSimpleSpan (span: Span): String = span match {
      case e @ CitationLink(ref,label,_)  => fmt.withCitation(ref)(c => fmt.footnote(e,label,c.content,c.options))
      case e @ FootnoteLink(ref,label,_)  => fmt.withFootnote(ref)(f => fmt.footnote(e,label,f.content,f.options))
      case SectionNumber(pos, opt)        => fmt.child(Text(pos.mkString(".") + " ", opt + Style.sectionNumber))
      case e @ Image(target,_,_,_,_,_) =>
        val uri = target match {
          case it: InternalTarget => it.relativeTo(fmt.path).absolutePath.toString
          case et: ExternalTarget => et.url
        }
        fmt.externalGraphic(e, uri, None, None) // ignore intrinsic size and rely on styles for sizing
      case icon: Icon                     => fmt.rawElement("fo:inline", icon, icon.codePointAsEntity)
      case e: Leader                      => fmt.textElement("fo:leader", e, "", "leader-pattern"->"dots", "padding-left" -> "2mm", "padding-right" -> "2mm")
      case PageNumberCitation(target,_)     => s"""<fo:page-number-citation ref-id="${fmt.buildId(target.relativeTo(fmt.path).absolutePath)}" />"""
      case LineBreak(_)                   => "&#x2028;"
      case TemplateElement(elem,indent,_) => fmt.withMinIndentation(indent)(_.child(elem))

      case WithFallback(fallback)         => fmt.child(fallback)
      case _                              => ""
    }
    
    def addRowStyles (rows: Seq[Row]): Seq[Row] = rows.zipWithIndex.map {
      case (row, index) => row.mergeOptions(Styles(if (index % 2 == 0) "cell-odd" else "cell-even")) // switch to 1-base
    }

    def renderTableElement (elem: TableElement): String = elem match {
      case e @ TableHead(rows,_)   => fmt.indentedElement("fo:table-header", e, rows)
      case e @ TableBody(rows,_)   => fmt.indentedElement("fo:table-body", e, addRowStyles(rows))
      case Caption(_,_)            => "" // replaced by Table renderer
      case Columns(_,_)            => "" // replaced by Table renderer
      case e: Column               => fmt.emptyElement("fo:table-column", e)
      case e @ Row(cells,_)        => fmt.indentedElement("fo:table-row", e, cells)
      case e @ Cell(_, content, colspan, rowspan, _) => 
        fmt.indentedElement("fo:table-cell", e, content, fmt.optAttributes(
          "number-columns-spanned" -> noneIfDefault(colspan,1),
          "number-rows-spanned"    -> noneIfDefault(rowspan,1)):_*
        )
    }

    def renderNavigationItem (elem: NavigationItem): String = {
      
      val keepWithNext = Styles("keepWithNext")
      val keepWithPrev = Styles("keepWithPrevious")
      
      def avoidOrphan (content: Seq[NavigationItem]): Seq[NavigationItem] = content match {
        case init :+ last if last.content.isEmpty => init :+ last.mergeOptions(keepWithPrev)
        case empty => empty
      }
      
      elem match {
        case l: NavigationItem if l.options.styles.contains("bookmark") => fmt.bookmark(l)
        case NavigationHeader(title, content, opt) =>
          fmt.childPerLine(Paragraph(title.content, Style.nav + keepWithNext + opt) +: avoidOrphan(content))
        case NavigationLink(title, target: InternalTarget, content, _, opt) =>
          val link = SpanLink(
            content = title.content :+ Leader() :+ PageNumberCitation(target),
            target = target
          )
          val keep = if (content.isEmpty) NoOpt else keepWithNext
          fmt.childPerLine(Paragraph(Seq(link), Style.nav + keep + opt) +: avoidOrphan(content))
        case _ => ""
      }
    }

    def renderUnresolvedReference (ref: Reference): String = {
      fmt.child(InvalidElement(s"unresolved reference: $ref", ref.source).asSpan)
    }

    def renderInvalidElement (elem: Invalid[_ <: Element]): String = elem match {
      case InvalidBlock(msg, fallback, opt) =>
        fmt.forMessage(msg)(fmt.child(Paragraph(List(msg), opt))) + fmt.child(fallback)
      case e =>
        fmt.forMessage(e.message)(fmt.child(e.message) + " ") + fmt.child(e.fallback)
    }

    def renderRuntimeMessage (message: RuntimeMessage): String = {
      fmt.forMessage(message) {
        fmt.text(message.copy(options = message.options + Styles(message.level.toString.toLowerCase)), message.content)
      }
    }
    
    def renderPreamble (p: Preamble): String = {
      s"""
        |
        |<fo:block id="${fmt.buildId(fmt.path)}" page-break-before="always">
        |  <fo:marker marker-class-name="chapter"><fo:block>${fmt.text(p.title)}</fo:block></fo:marker>
        |</fo:block>""".stripMargin
    }

    element match {
      case e: RuntimeMessage        => renderRuntimeMessage(e)
      case e: Table                 => renderTable(e)
      case e: TableElement          => renderTableElement(e)
      case e: NavigationItem        => renderNavigationItem(e)
      case e: Reference             => renderUnresolvedReference(e)
      case e: Invalid[_]            => renderInvalidElement(e)
      case e: BlockContainer        => renderBlockContainer(e)
      case e: SpanContainer         => renderSpanContainer(e)
      case e: ListContainer         => renderListContainer(e)
      case e: TextContainer         => renderTextContainer(e)
      case e: TemplateSpanContainer => renderTemplateSpanContainer(e)
      case e: Block                 => renderSimpleBlock(e)
      case e: Span                  => renderSimpleSpan(e)

      case _                        => ""
    }
  }

}
