package soxx.scrappers

import javax.inject._

import akka.actor._
import scala.concurrent._
import scala.concurrent.duration._

import play.api.libs.ws._
import play.api.inject.ApplicationLifecycle
import play.api.Logger

import org.mongodb.scala._
import org.mongodb.scala.model._
import scala.util._
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

    context.actorOf(Props(new SafebooruScrapper), "safebooru-scrapper")

    lifecycle.addStopHook { () =>
      Future { context.stop(self) }
    }
  }

  override def receive = {
    case _ => ???
  }
}
