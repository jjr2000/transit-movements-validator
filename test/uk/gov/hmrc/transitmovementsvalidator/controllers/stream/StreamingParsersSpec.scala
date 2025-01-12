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

package uk.gov.hmrc.transitmovements.controllers.stream

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.scalacheck.Gen
import org.scalatest.OptionValues
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.Logging
import play.api.http.HeaderNames
import play.api.http.Status.OK
import play.api.libs.Files.SingletonTemporaryFileCreator
import play.api.mvc.Action
import play.api.mvc.BaseController
import play.api.test.FakeHeaders
import play.api.test.FakeRequest
import play.api.test.Helpers.contentAsString
import play.api.test.Helpers.defaultAwaitTimeout
import play.api.test.Helpers.status
import play.api.test.Helpers.stubControllerComponents
import uk.gov.hmrc.transitmovementsvalidator.base.TestActorSystem
import uk.gov.hmrc.transitmovementsvalidator.base.TestSourceProvider
import uk.gov.hmrc.transitmovementsvalidator.controllers.stream.StreamingParsers

import java.nio.charset.StandardCharsets
import scala.annotation.tailrec
import scala.concurrent.Future

class StreamingParsersSpec extends AnyFreeSpec with Matchers with TestActorSystem with OptionValues with TestSourceProvider {

  lazy val headers = FakeHeaders(Seq(HeaderNames.CONTENT_TYPE -> "text/plain", HeaderNames.ACCEPT -> "application/vnd.hmrc.2.0+json"))

  object Harness extends BaseController with StreamingParsers with Logging {

    override val controllerComponents = stubControllerComponents()
    implicit val temporaryFileCreator = SingletonTemporaryFileCreator
    implicit val materializer         = Materializer(TestActorSystem.system)

    def testFromMemory: Action[Source[ByteString, _]] = Action.async(streamFromMemory) {
      request => result.apply(request).run(request.body)(materializer)
    }

    def result: Action[String] = Action.async(parse.text) {
      request =>
        Future.successful(Ok(request.body))
    }

    def resultStream: Action[Source[ByteString, _]] = Action.stream {
      request =>
        (for {
          a <- request.body.runWith(Sink.head)
          b <- request.body.runWith(Sink.head)
        } yield (a ++ b).utf8String)
          .map(
            r => Ok(r)
          )
    }

  }

  @tailrec
  private def generateByteString(kb: Int, accumulator: ByteString = ByteString.empty): ByteString =
    if (kb <= 0) accumulator
    else {
      lazy val valueAsByte: Byte = (kb % 10).toString.getBytes(StandardCharsets.UTF_8)(0) // one byte each
      generateByteString(kb - 1, ByteString.fromArray(Array.fill(1024)(valueAsByte)) ++ accumulator)
    }

  private def generateSource(byteString: ByteString): Source[ByteString, NotUsed] =
    Source(byteString.grouped(1024).toSeq)

  "Streaming" - {
    "from Memory" - {
      (1 to 5).foreach {
        value =>
          s"~$value kb string is created" in {
            val byteString = generateByteString(value)
            val request    = FakeRequest("POST", "/", headers, generateSource(byteString))
            val result     = Harness.testFromMemory()(request)
            status(result) mustBe OK
            contentAsString(result) mustBe byteString.utf8String
          }
      }
    }

    "via the stream extension method" in {
      val string  = Gen.stringOfN(20, Gen.alphaNumChar).sample.value
      val request = FakeRequest("POST", "/", headers, singleUseStringSource(string))
      val result  = Harness.resultStream()(request)
      status(result) mustBe OK
      contentAsString(result) mustBe (string ++ string)
    }
  }

}
