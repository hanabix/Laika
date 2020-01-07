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

package laika.parse.code.languages

import laika.bundle.SyntaxHighlighter
import laika.parse.code.CodeSpan
import laika.parse.text.TextParsers.any

/**
  * @author Jens Halm
  */
object HTML {

  val highlighter: SyntaxHighlighter = SyntaxHighlighter("html")(any ^^ { txt => Seq(CodeSpan(txt)) })
  
}