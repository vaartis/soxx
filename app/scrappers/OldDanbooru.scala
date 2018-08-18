package soxx.scrappers

import scala.concurrent._

import play.api.libs.json._, play.api.inject.Injector
import scala.xml.Elem

class OldDanbooruScrapper(
  name: String,
  baseUrl: String,
  favicon: String,

  injector: Injector
) extends GenericScrapper(name, baseUrl, favicon, injector)
    with traits.XMLImageCount {

  case class OldDanbooruImage(
    id: Int,
    height: Int,
    width: Int,
    score: Int,
    image: String,
    directory: String,
    tags: String,
    hash: String
  )

  // Boards tend to not allow more then 8 connections,
  // if you see that the indexing just stops, this might be
  // the reason it doesnt progress (e.g. at 10 most just hang)
  override val maxPageFetchingConcurrency = 8
  override val maxImageFetchingConcurrency = 8

  override type ScrapperImage = OldDanbooruImage

  override implicit val imageFormat = Json.format[OldDanbooruImage]

  override val apiAddition = "index.php?page=dapi&s=post&q=index"

  override val imageCountAddition = f"$apiAddition&limit=1"

  override def extractImageCount(from: Elem) =
    (from \\ "posts" \ "@count").map{ _.text }.head.toInt

  override def getPageImages(currentPage: Int): Future[Seq[OldDanbooruImage]] =
    ws
      .url(s"${baseUrl}/${apiAddition}&pid=${currentPage.toString}&json=1")
      .get()
      .map(_.json.as[Seq[OldDanbooruImage]])

  override def scrapperImageToImage(img: OldDanbooruImage): Option[Image] = {
    import io.scalaland.chimney.dsl._

    Some(
      img.into[Image]
        .withFieldRenamed(_.hash, _.md5)
        .withFieldComputed(_.tags, _.tags.split(" ").toSeq)
        .withFieldConst(_.extension, img.image.substring(img.image.lastIndexOf('.')))
        .withFieldConst(
          _.from,
          Seq(
            From(
              id = img.id,
              name = name,
              score = img.score,
              post = f"${baseUrl}/index.php?page=post&s=view&id=${img.id}",
              image = f"${baseUrl}/images/${img.directory}/${img.image}",
              thumbnail = f"${baseUrl}/thumbnails/${img.directory}/thumbnail_${img.image}"
            )
          )
        ).transform
    )
  }

}
