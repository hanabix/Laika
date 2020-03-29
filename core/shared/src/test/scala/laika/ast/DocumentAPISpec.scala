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

package laika.ast

import laika.api.MarkupParser
import laika.api.builder.OperationConfig
import laika.ast.helper.ModelBuilder
import laika.format.Markdown
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DocumentAPISpec extends AnyFlatSpec 
                      with Matchers
                      with ModelBuilder {

  
  val parser = MarkupParser.of(Markdown).build
  
  "The Document API" should "allow to specify a title in a config section" in {
    val markup = """{% title: Foo and Bar %}
      |
      |# Ignored Title
      |
      |Some text
      |
      |## Section
      |
      |Some more text""".stripMargin

    parser.parse(markup).toOption.get.title should be (Some(SpanSequence("Foo and Bar")))
  }
  
  it should "use the title from the first headline if it is not overridden in a config section" in {
    val markup = """# Title
      |
      |Some text
      |
      |## Section
      |
      |Some more text""".stripMargin

    parser.parse(markup).toOption.get.title should be (Some(SpanSequence("Title")))
  }
  
  it should "return an empty list if there is neither a structure with a title nor a title in a config section" in {
    val markup = """# Section 1
      |
      |Some text
      |
      |# Section 2
      |
      |Some more text""".stripMargin

    parser.parse(markup).toOption.get.title should be (None)
  }
  
  it should "produce the same result when rewriting a document once or twice" in {
    val markup = """# Section 1
      |
      |Some text
      |
      |# Section 2
      |
      |Some more text""".stripMargin
    
    val doc = parser.parseUnresolved(markup).toOption.get.document
    
    val rewritten1 = doc.rewrite(OperationConfig.default.rewriteRulesFor(doc))
    val rewritten2 = rewritten1.rewrite(OperationConfig.default.rewriteRulesFor(rewritten1))
    rewritten1.content should be (rewritten2.content)
  }
  
  it should "allow to rewrite the document using a custom rule" in {
    val markup = """# Section 1
      |
      |Some text
      |
      |# Section 2
      |
      |Some more text""".stripMargin
    
    val raw = parser.parseUnresolved(markup).toOption.get.document
    val cursor = DocumentCursor(raw)
    val testRule = RewriteRules.forSpans {
      case Text("Some text",_) => Replace(Text("Swapped"))
    }
    val rules = testRule ++ OperationConfig.default.rewriteRulesFor(cursor.target)
    val rewritten = raw rewrite rules
    rewritten.content should be (root(
      Section(Header(1, List(Text("Section 1")), Id("section-1") + Styles("section")), List(p("Swapped"))),
      Section(Header(1, List(Text("Section 2")), Id("section-2") + Styles("section")), List(p("Some more text")))
    ))
  }
  
  
}
