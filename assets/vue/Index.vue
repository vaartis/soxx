<template>
    <div>
        <div class="container-fluid" v-if="pages == null || pages.length > 0">
            <div class="row">
                <div class="col-sm-6 col-md-4 col-lg-3 col-xl-2" v-for="image in images" v-bind:key="image._id">
                    <div class="card">
                        <!-- Hacky but works! Looks beter then object-fit too  -->
                        <!-- Just using the first "from" may not be the best idea -->
                        <a v-bind:href="`/image/${image._id}`"
                           data-toggle="modal" v-bind:data-target="`#image-modal-${image._id}`">
                            <div class="card-img-top img-card"
                                 v-bind:style="{backgroundImage: `url(${image.image})`}">
                            </div>
                        </a>
                        <ImageModal v-bind:image="image" />
                        <div class="card-body">
                            <div class="card-header">
                                <a v-for="from in image.from" v-bind:key="from.name"
                                   v-bind:href="from.post" v-bind:title="from.name">
                                    <img v-bind:src="favicon(from.name)" />
                                </a>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        </div>
        <div class="text-center" v-else>
            <h2 v-once>
                {{ genNoResultsString()  }}
            </h2>
            <sub class="text-muted">
                No images found
            </sub>
        </div>

        <div>
            <ul class="pagination justify-content-center">
                <li class="page-item" v-for="page in pages">
                    <a class="page-link" v-bind:href="computePageUrl(page)" v-on:click.prevent="goToPage(page)"> {{ page }} </a>
                </li>
            </ul>
        </div>
    </div>
</template>

<script>
 import urlListener from "url-listener";
 import _ from "lodash";
 import URI from "urijs";

 import iziToast from "izitoast";
 import "../node_modules/izitoast/dist/css/iziToast.min.css";

 import ImageModal from "./ImageModal.vue";

 Vue.config.productionTip = false

 export default {
     data() {
         return {
             imboard_info: {},
             images: [],
             // Actually just page numbers since you kinda can't use lodash in templates(?)
             // We use null as a starting value to indicate that the search did not start yet.
             // If this is set to an empty array, then we render a string stating that no images were found
             pages: null
         }
     },

     methods: {
         favicon(imgFrom) {
             return this.imboard_info[imgFrom].favicon;
         },

         updateImagesFromQueryString() {
             let queryUrl = new URI(window.location);
             let imagesUrl = new URI(queryUrl.origin() + "/api/v1/images");

             if (queryUrl.hasQuery("query")) {
                 let searchString = queryUrl.query(true)["query"];
                 imagesUrl.addQuery("query", searchString);
             }

             let page = 1;
             if (queryUrl.hasQuery("page")) {
                 page = queryUrl.query(true)["page"];
                 imagesUrl.addQuery("offset", page * 25); // The default image count is 25
             }

             fetch(imagesUrl)
                 .then(resp => resp.json())
                 .then(r => {
                     if (r.ok) {
                         this.images = r.result.images;

                         if (r.result.imageCount > 0) {
                             // Get a `range` of elements around the `index`
                             function getAround(array, index, range) {
                                 var least = index - range - 1;
                                 least = (least < 0) ? 0 : least;
                                 return _.slice(array, least, least + (range * 2) + 1);
                             }


                             this.pages = getAround(
                                 _.range(1, Math.floor(r.result.imageCount / 25)), // FIXME: make page size configurable
                                 page,
                                 5
                             );

                             // Go to the top of the page
                             window.scroll(0, 0);
                         } else {
                             this.pages = [];
                         }
                     } else {
                         iziToast.error({
                             title: "Search error",
                             message: r.error,
                             layout: 2,
                             drag: false
                         });
                     }
                 });
         },

         computePageUrl(page) {
             let queryUrl = new URI(window.location);
             queryUrl.setQuery("page", page);

             return queryUrl.toString();
         },

         goToPage(page) {
             window.history.pushState({page: page}, '', this.computePageUrl(page));
         },

         genNoResultsString() {
             let strings = [
                 "Can't help you with this one",
                 "Nothing here",
                 "Nobody here but us chickens!",
                 "検索結果ありません",
                 "検索するの結果は何もないよ",
                 "🔎🖼❌"
             ];

             return strings[_.random(0, strings.length - 1)];
         },
     },

     mounted() {
         let queryUrl = new URI(window.location);

         fetch("/api/v1/imboard_info")
             .then(resp => resp.json())
             .then(boards => {
                 _.forEach(boards, (board) => {
                     Vue.set(this.imboard_info, board._id, _.omit(board, "_id"));
                 })
             })
             .then(this.updateImagesFromQueryString)

         urlListener(event => {
             this.updateImagesFromQueryString();
         });
     },

     components: { ImageModal }
 }
</script>

<style>

 .img-card {
     height: 30em;
     width: auto;
     position: relative;
     background-position: 50% 50%;
     background-repeat: no-repeat;
     background-size: cover;
 }

</style>
