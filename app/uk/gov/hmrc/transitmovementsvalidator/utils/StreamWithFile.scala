/*
 * Copyright 2023 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.transitmovementsvalidator.utils

import akka.stream.{IOResult, Materializer}
import akka.stream.scaladsl.{FileIO, Source}
import akka.util.ByteString
import cats.data.EitherT
import cats.syntax.flatMap._
import play.api.Logging
import play.api.libs.Files.TemporaryFileCreator
import uk.gov.hmrc.transitmovementsvalidator.models.errors.PresentationError

import java.nio.file.Path
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

trait StreamWithFile {
  self: Logging =>

  def withReusableSource[R](
    src: Source[ByteString, _]
  )(
    block: Source[ByteString, _] => EitherT[Future, PresentationError, R]
  )(implicit temporaryFileCreator: TemporaryFileCreator, mat: Materializer, ec: ExecutionContext): EitherT[Future, PresentationError, R] = {
    val file = temporaryFileCreator.create()
    (for {
      _      <- writeToFile(file, src)
      result <- block(FileIO.fromPath(file))
    } yield result)
      .flatTap {
        _ =>
          file.delete()
          EitherT.rightT(())
      }

  }

  private def writeToFile(file: Path, src: Source[ByteString, _])(implicit
    mat: Materializer,
    ec: ExecutionContext
  ): EitherT[Future, PresentationError, IOResult] =
    EitherT(
      src
        .runWith(FileIO.toPath(file))
        .map(Right.apply)
        .recover {
          case NonFatal(thr) =>
            logger.error(s"Failed to create file stream: $thr", thr)
            Left(PresentationError.internalServiceError(cause = Some(thr)))
        }
    )

}
