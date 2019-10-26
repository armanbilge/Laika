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

import java.io._

import cats.effect.{Async, Resource}
import cats.implicits._
import laika.io.model._

import scala.io.Codec

/** Internal runtime for creating and writing to OutputStreams.
  * 
  * @author Jens Halm
  */
object OutputRuntime {

  /** Creates a Writer for the specified output model and writes the given string to it.
    */
  def write[F[_]: Async: Runtime] (result: String, output: TextOutput): F[Unit] = {
    output match {
      case StringOutput(_) => Async[F].unit
      case TextFileOutput(file, _, codec) => Runtime[F].runBlocking {
        fileWriter(file, codec).use { writer =>
          Async[F].delay(writer.write(result))
        }
      }
      case CharStreamOutput(stream, _, autoClose, codec) =>
        Runtime[F].runBlocking {
          val streamF = Async[F].pure(stream)
          val resource = if (autoClose) Resource.fromAutoCloseable(streamF) else Resource.liftF(streamF)
          resource.map(out => new BufferedWriter(new OutputStreamWriter(out, codec.charSet))).use { writer =>
            Async[F].delay {
              writer.write(result)
              writer.flush()
            }
          }
        }
    }
  }

  private def fileWriter[F[_]: Async] (file: File, codec: Codec): Resource[F, Writer] = Resource.fromAutoCloseable(Async[F].delay {
    new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), codec.charSet))
  })

  /** Creates a directory for the specified file, including parent directories
    * of that file if they do not exist yet.
    */
  def createDirectory[F[_]: Async] (file: File): F[Unit] = 
    Async[F].delay(file.exists || file.mkdirs()).flatMap(if (_) Async[F].unit 
    else Async[F].raiseError(new IOException(s"Unable to create directory ${file.getAbsolutePath}")))
 
  def binaryFileResource[F[_]: Async] (file: File): Resource[F, OutputStream] = 
    Resource.fromAutoCloseable(Async[F].delay(new BufferedOutputStream(new FileOutputStream(file))))

  def binaryStreamResource[F[_]: Async] (stream: F[OutputStream], autoClose: Boolean): Resource[F, OutputStream] =
    if (autoClose) Resource.fromAutoCloseable(stream) else Resource.liftF(stream)

}
