package soxx.s3

import org.scalatest._
import org.scalatest.fixture
import akka.testkit.{ TestProbe }
import io.findify.s3mock.S3Mock

import java.io.ByteArrayInputStream
import scala.concurrent.Await
import scala.concurrent.duration._

import akka.actor._
import play.api.Application
import play.api.libs.ws.WSClient
import play.api.inject.BindingKey
import play.api.inject.guice.GuiceApplicationBuilder

class S3UploaderSpec extends fixture.FlatSpec
    with Matchers {

  case class FixtureParam(
    app: Application,
    actorSystem: ActorSystem,
    s3Uploader: ActorRef,
    ws: WSClient
  )

  override def withFixture(test: OneArgTest) = {
    val s3 = S3Mock(port = 9999)
    s3.start

    lazy val app = GuiceApplicationBuilder()
      .configure(
        "soxx.s3.enabled" -> true,
        "soxx.s3.endpoint" -> "http://localhost:9999",
        "soxx.s3.access-key" -> "test_access_key",
        "soxx.s3.secret-key" -> "test_secret_key"
      ).build
    lazy val actorSystem = app.injector.instanceOf[ActorSystem]
    lazy val s3Uploader = app.injector.instanceOf(BindingKey(classOf[ActorRef]).qualifiedWith("s3-uploader"))
    lazy val ws = app.injector.instanceOf[WSClient]

    try super.withFixture(test.toNoArgTest(FixtureParam(app, actorSystem, s3Uploader, ws)))
    finally {
      s3.stop
    }
  }

  behavior of "S3 uploader"

  it should "not report nonexistent images as existing" in { f =>
    val probe = TestProbe()(f.actorSystem)
    probe.send(f.s3Uploader, ImageExists("test"))
    probe.expectMsg(5.seconds, false)
  }

  it should "upload correctly" in { f =>
    val testStr = "CONTENT"

    val probe = TestProbe()(f.actorSystem)

    probe.send(f.s3Uploader, UploadImage("test", new ByteArrayInputStream(testStr.getBytes), testStr.length, "text/plain"))
    probe.expectMsg(5.seconds, true)

    probe.send(f.s3Uploader, ImageExists("test"))
    probe.expectMsg(5.seconds, true)

    val resp = Await.result(f.ws.url("http://localhost:9999/soxx-images/test").get(), 5.seconds)

    resp.body shouldBe testStr
    resp.contentType shouldBe "text/plain"
  }
}
