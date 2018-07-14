package soxx.scrappers

import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import akka.testkit.{ ImplicitSender, TestKit }
import mockws.{MockWS, MockWSHelpers}
import scala.concurrent.Await

import scala.concurrent.duration._
import java.nio.file.{ Files, Paths }

import resource._
import akka.actor._
import play.api.mvc.Results._
import play.api.libs.ws.WSClient
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.inject.bind

import soxx.mongowrapper._

class ScrappersSpec extends TestKit(ActorSystem("ScrappersSpec"))
    with ImplicitSender
    with FlatSpecLike
    with Matchers
    with BeforeAndAfterAll
    with MockWSHelpers
    with ScalaFutures {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  trait ScrapperTest {
    // The WS mock addresses
    def ws: MockWS

    // The actor name used
    def actorName: String

    // The class to test
    def scrapperClass: Class[_ <: GenericScrapper]

    val tempDir = Files.createTempDirectory(self.path.name)

    lazy val app =
      new GuiceApplicationBuilder()
        .configure(
          "soxx.mongo.dbName" -> "soxx_test",

          "soxx.scrappers.downloadDirectory" -> tempDir.toString,
        )
        .overrides(bind[WSClient].to(ws))
        .build

    lazy val db = app.injector.instanceOf[Mongo].db

    val actor = system.actorOf(
      Props(
        scrapperClass,
        actorName,
        "http://example.com",
        "favicon.ico",
        app.injector
      ),
      actorName
    )

    try {
      // The test itself

      actor ! StartIndexing(1, Some(1))

      actor ! ScrapperStatusMsg
      expectMsg(5.seconds, ScrapperStatus(actorName, true, false)) // Scrapping process is started
      expectMsg(5.seconds, ScrapperStatus(actorName, false, false)) // The indexing has finished

      actor ! StartDownloading

      actor ! ScrapperStatusMsg
      expectMsg(5.seconds, ScrapperStatus(actorName, false, true)) // Downloading process is started
      expectMsg(5.seconds, ScrapperStatus(actorName, false, false)) // Downloading process has finished

      val imagePath = Paths.get(tempDir.toString, "image_hash.jpg")

      Files.exists(imagePath) shouldBe true

      for (src <- managed(scala.io.Source.fromFile(imagePath.toString))) {
        src.mkString shouldBe "DOWNLOADED"
      }
    } finally {
      // Cleanup

      Await.result(db.drop().toFuture, 5.seconds)
    }
  }

  "OldDanbooru scrapper" should "index and download images" in new ScrapperTest {
    override def actorName = "test_old_danbooru"
    override def scrapperClass = classOf[OldDanbooruScrapper]
    override def ws = MockWS {
      case ("GET", "http://example.com/index.php?page=dapi&s=post&q=index") =>
        Action { Ok("""<posts count="1"></posts>""") }
      case ("GET", "http://example.com/index.php?page=dapi&s=post&q=index&pid=1&json=1") =>
        Action {
          Ok(
            (for (src <- managed(scala.io.Source.fromInputStream(getClass.getResourceAsStream("/scrappers/old_danbooru_image.json"))))
              yield src.mkString
            ).opt.get
          )
        }
      case ("GET", "http://example.com/images/2486/image_name.jpg") =>
        Action { Ok("DOWNLOADED")  }
    }
  }


  "NewDanbooru scrapper" should "index and download images" in new ScrapperTest {
    override def actorName = "test_new_danbooru"
    override def scrapperClass = classOf[NewDanbooruScrapper]
    override def ws = MockWS {
      case ("GET", "http://example.com/counts/posts.json") =>
        Action { Ok("""{"counts": {"posts": 1}}""") }
      case ("GET", "http://example.com/posts.json") =>
        Action {
          Ok(
            (for (src <- managed(scala.io.Source.fromInputStream(getClass.getResourceAsStream("/scrappers/new_danbooru_image.json"))))
              yield src.mkString
            ).opt.get
          )
        }
      case ("GET", "http://example.com/data/image_hash.jpg") =>
        Action { Ok("DOWNLOADED")  }
    }
  }

  "Moebooru scrapper" should "index and download images" in new ScrapperTest {
    override def actorName = "test_new_moebooru"
    override def scrapperClass = classOf[MoebooruScrapper]
    override def ws = MockWS {
      case ("GET", "http://example.com/post.xml") =>
        Action { Ok("""<posts count="1"></posts>""") }
      case ("GET", "http://example.com/post.json") =>
        Action {
          Ok(
            (for (src <- managed(scala.io.Source.fromInputStream(getClass.getResourceAsStream("/scrappers/moebooru_image.json"))))
              yield src.mkString
            ).opt.get
          )
        }
      case ("GET", "http://example.com/data/image_hash.jpg") =>
        Action { Ok("DOWNLOADED")  }
    }
  }
}
