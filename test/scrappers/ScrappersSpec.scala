package soxx.scrappers

import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import akka.testkit.{ ImplicitSender, TestKit }
import mockws.{MockWS, MockWSHelpers}
import scala.concurrent.Await

import scala.concurrent.duration._
import java.nio.file.{ Files, Paths }

import akka.actor._
import play.api.mvc.Results._
import play.api.libs.json.Json
import play.api.libs.ws.WSClient
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.inject.bind
import org.mongodb.scala.MongoClient

import soxx.mongowrapper._

class ScrappersSpec extends TestKit(ActorSystem("ScrappersSpec"))
    with ImplicitSender
    with FlatSpecLike
    with Matchers
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with MockWSHelpers
    with ScalaFutures {

  override def beforeEach {
    Await.result(MongoClient().getDatabase("soxx_test").drop().toFuture, 5.seconds)
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "OldDanbooru scrapper" should "index and download images" in {
    val actorName = "test_old_danbooru"

    val ws = MockWS {
      case ("GET", "http://example.com/index.php?page=dapi&s=post&q=index") =>
        Action { Ok("""<posts count="1"></posts>""") }
      case ("GET", "http://example.com/index.php?page=dapi&s=post&q=index&pid=1&json=1") =>
        Action {
          Ok({
            val src = scala.io.Source.fromInputStream(getClass.getResourceAsStream("/scrappers/old_danbooru_image.json"))
            try src.mkString finally src.close
          })
        }
      case ("GET", "http://example.com/images/2486/image_name.jpg") =>
        Action { Ok("DOWNLOADED")  }
    }

    val tempDir = Files.createTempDirectory(self.path.name)

    val app =
      new GuiceApplicationBuilder()
        .configure(
          "soxx.mongo.dbName" -> "soxx_test",

          "soxx.scrappers.downloadDirectory" -> tempDir.toString,
        )
        .overrides(bind[WSClient].to(ws))

    val actor = system.actorOf(
      Props(
        classOf[OldDanbooruScrapper],
        actorName,
        "http://example.com",
        "favicon.ico",
        app.injector
      ),
      actorName
    )

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

    val src = scala.io.Source.fromFile(imagePath.toString)
    try src.mkString shouldBe "DOWNLOADED" finally src.close
  }


  "NewDanbooru scrapper" should "index and download images" in {
    val actorName = "test_new_danbooru"

    val ws = MockWS {
      case ("GET", "http://example.com/counts/posts.json") =>
        Action { Ok("""{"counts": {"posts": 1}}""") }
      case ("GET", "http://example.com/posts.json") =>
        Action {
          Ok({
            val src = scala.io.Source.fromInputStream(getClass.getResourceAsStream("/scrappers/new_danbooru_image.json"))
            try src.mkString finally src.close
          })
        }
      case ("GET", "http://example.com/data/image_hash.jpg") =>
        Action { Ok("DOWNLOADED")  }
    }

    val tempDir = Files.createTempDirectory(self.path.name)

    val app =
      new GuiceApplicationBuilder()
        .configure(
          "soxx.mongo.dbName" -> "soxx_test",

          "soxx.scrappers.downloadDirectory" -> tempDir.toString,
        )
        .overrides(bind[WSClient].to(ws))

    val actor = system.actorOf(
      Props(
        classOf[NewDanbooruScrapper],
        actorName,
        "http://example.com",
        "favicon.ico",
        app.injector
      ),
      actorName
    )

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

    val src = scala.io.Source.fromFile(imagePath.toString)
    try src.mkString shouldBe "DOWNLOADED" finally src.close
  }

  "Moebooru scrapper" should "index and download images" in {
    val actorName = "test_new_moebooru"

    val ws = MockWS {
      case ("GET", "http://example.com/post.xml") =>
        Action { Ok("""<posts count="1"></posts>""") }
      case ("GET", "http://example.com/post.json") =>
        Action {
          Ok({
            val src = scala.io.Source.fromInputStream(getClass.getResourceAsStream("/scrappers/moebooru_image.json"))
            try src.mkString finally src.close
          })
        }
      case ("GET", "http://example.com/data/image_hash.jpg") =>
        Action { Ok("DOWNLOADED")  }
    }

    val tempDir = Files.createTempDirectory(self.path.name)

    val app =
      new GuiceApplicationBuilder()
        .configure(
          "soxx.mongo.dbName" -> "soxx_test",

          "soxx.scrappers.downloadDirectory" -> tempDir.toString,
        )
        .overrides(bind[WSClient].to(ws))

    val actor = system.actorOf(
      Props(
        classOf[MoebooruScrapper],
        actorName,
        "http://example.com",
        "favicon.ico",
        app.injector
      ),
      actorName
    )

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

    val src = scala.io.Source.fromFile(imagePath.toString)
    try src.mkString shouldBe "DOWNLOADED" finally src.close
  }
}
