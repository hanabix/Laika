/*
 * Copyright 2013-2018 the original author or authors.
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

package laika.render.epub

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

import laika.ast._

/** Renders the content of an EPUB Package document (OPF).
  *
  * @author Jens Halm
  */
class OPFRenderer {


  /** Inserts the specified spine references into the OPF document template
    * and returns the content of the entire OPF file.
    */
  def fileContent (uuid: String, title: String, timestamp: String, docRefs: Seq[DocumentRef]): String =
    s"""<?xml version="1.0" encoding="UTF-8"?>
       |<package
       |    version="3.0"
       |    xmlns="http://www.idpf.org/2007/opf"
       |    unique-identifier="epub-id-1"
       |    prefix="ibooks: http://vocabulary.itunes.apple.com/rdf/ibooks/vocabulary-extensions-1.0/">
       |  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
       |    <dc:identifier id="epub-id-1">urn:uuid:$uuid</dc:identifier>
       |    <dc:title>$title</dc:title>
       |    <dc:date id="epub-date">$timestamp</dc:date>
       |    <dc:language>en-GB</dc:language>
       |    <meta property="dcterms:modified">$timestamp</meta>
       |  </metadata>
       |  <manifest>
       |    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml" />
       |    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav" />
       |${docRefs.map { ref => s"""    <item id="${ref.id}" href="${ref.link}" media-type="${ref.mediaType}" />""" }.mkString("\n")}
       |  </manifest>
       |  <spine toc="ncx">
       |${docRefs.filter(_.isSpine).map { ref => s"""    <itemref idref="${ref.id}" />""" }.mkString("\n")}
       |  </spine>
       |  <guide>
       |    <reference type="toc" title="Table of Content" href="nav.xhtml" />
       |  </guide>
       |</package>
    """.stripMargin

  private case class DocumentRef (path: Path, mediaType: String, isSpine: Boolean, forceXhtml: Boolean = false) {

    val link = BookNavigation.fullPath(path, forceXhtml)

    val id = link.drop(8).replaceAllLiterally("/", "_").replaceAllLiterally(".", "_")

  }

  /** Renders the content of an EPUB Package document (OPF) generated from
    * the specified document tree.
    */
  def render (tree: DocumentTree, uuid: String, publicationTime: Instant): String = {

    def spineRefs (root: DocumentTree): Seq[DocumentRef] = {
      root.content.flatMap {
        case sub: DocumentTree => spineRefs(sub)
        case doc: Document => Seq(DocumentRef(doc.path, "application/xhtml+xml", isSpine = true, forceXhtml = true))
      } ++
      root.additionalContent.filter(c => MimeTypes.supportedTypes.contains(c.path.suffix)).collect {
        case StaticDocument(input) => DocumentRef(input.path, MimeTypes.supportedTypes(input.path.suffix), isSpine = false)
      }
    }

    val title = if (tree.title.isEmpty) "UNTITLED" else SpanSequence(tree.title).extractText
    val formattedPubTime = DateTimeFormatter.ISO_INSTANT.format(publicationTime.truncatedTo(ChronoUnit.SECONDS))
    fileContent(uuid, title, formattedPubTime, spineRefs(tree))
  }


}