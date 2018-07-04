package soxx.scrappers

import javax.inject._

import akka.actor._
import scala.concurrent._

import play.api.libs.ws._
import play.api.inject.ApplicationLifecycle
import play.api.Logger
import org.mongodb.scala._
import org.mongodb.scala.model._

import soxx.mongowrapper._

@Singleton
class ScrapperSupervisor @Inject()
  (
    implicit ec: ExecutionContext,
    ws: WSClient,
    mongo: Mongo,
    lifecycle: ApplicationLifecycle
  ) extends Actor {

  override val supervisorStrategy = (new StoppingSupervisorStrategy).create()
  val logger = Logger(this.getClass)

  override def preStart() {
    mongo.db
      .getCollection("images")
      .createIndexes(
        Seq(
          IndexModel(Document("originalID" -> 1)),
          IndexModel(Document("from" -> 1)),
          IndexModel(Document("md5" -> 1))
        )
      )
      .subscribe(
        (_: String) => {
          logger.info(f"Created/Updated image indexes")
        }
      )

    // Start scrapper actors
    Seq(
      // Old-danbooru-like
      (classOf[SafebooruScrapper], "safebooru-scrapper"),
      (classOf[FurrybooruScrapper], "furrybooru-scrapper"),

      // Moebooru-like
      // They mostly don't give images out to links,
      // so they need to be downloaded
      (classOf[KonachanScrapper], "konachan-scrapper"),
      (classOf[YandereScrapper], "yandere-scrapper"),
      // (classOf[SakugabooruScrapper], "sakugabooru-scrapper"),

      // New-danbooru-like
      (classOf[DanbooruScrapper], "danbooru-scrapper")
    ).foreach { case (scrapperClass, name) =>
        context.actorOf(
          Props(
            scrapperClass,
            implicitly[WSClient],
            implicitly[Mongo],
            implicitly[ExecutionContext]
          ),
          name
        )

        // Names really should be moved into something that is statically accessible
        // and can be made virtual. This is possible by implementing a trait
        // and creating companion objects for each class, but that's too
        // much code for too little benefit
    }

    lifecycle.addStopHook { () =>
      Future { context.stop(self) }
    }
  }

  override def receive = {
    case _ => ???
  }
}
