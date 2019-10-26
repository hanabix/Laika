/*
 * Copyright 2012-2019 the original author or authors.
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

package laika.io.runtime

import cats.Applicative
import cats.implicits._

/** Utility for explicitly producing a batch of operations as an optimization step
  * over relying solely on the thread pool.
  *
  * @author Jens Halm
  */
object BatchRuntime {

  /** Splits the specified operations into batches based on the given
    * desired parallelism.
    */
  def createBatches[F[_]: Applicative, A] (ops: Vector[F[A]], parallelism: Int): Vector[F[Vector[A]]] = {
    val mod = ops.size % parallelism
    val loSize = ops.size / parallelism
    val hiSize = loSize + 1
    val (hi,lo) = ops.splitAt(mod * hiSize)
    val hiBatch = if (mod > 0)    hi.grouped(hiSize) else Vector()
    val loBatch = if (loSize > 0) lo.grouped(loSize) else Vector()
    hiBatch.toVector.map(_.sequence) ++ loBatch.toVector.map(_.sequence)
  }

}
