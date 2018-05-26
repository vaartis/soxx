<template>
    <div class="modal fade" role="dialog" tabIndex="-1"
         v-bind:id="`image-modal-${this.image._id}`">
        <div class="modal-dialog modal-lg" role="document">
            <div class="modal-content">
                <div class="modal-header">
                    <h5 class="modal-title">Image {{ image._id }}</h5>
                    <button type="button" class="close" data-dismiss="modal">
                        &times;
                    </button>
                </div>
                <div class="modal-body">
                    <table class="table">
                        <tbody>
                            <tr>
                                <th scope="row">Height</th>
                                <td>{{ image.height }}</td>
                            </tr>
                            <tr>
                                <th scope="row">Width</th>
                                <td>{{ image.width }}</td>
                            </tr>
                            <tr>
                                <th scope="row">Tags</th>
                                <td>
                                    <a v-for="tag in image.tags"
                                       v-bind:href="constructLinkFromTag(tag)"
                                       v-on:click.prevent="searchTag(tag)"
                                       data-dismiss="modal"
                                       data-target="`#image-modal-${this.image._id}`">
                                        {{ tag }}
                                    </a>
                                </td>
                            </tr>
                            <tr>
                                <th scope="row">MD5</th>
                                <td>{{ image.md5 }}</td>
                            </tr>
                            <tr>
                                <th scope="row">Extension</th>
                                <td>{{ image.extension }}</td>
                            </tr>
                            <tr>
                                <th scope="row">Indexed on</th>
                                <td>{{ indexedOnDisplay }}</td>
                            </tr>
                        </tbody>
                    </table>
                    <a v-bind:href="image.from[0].image">
                        <img class="img-fluid" v-bind:src="image.from[0].image" />
                    </a>
                </div>
            </div>
        </div>
    </div>
</template>

<script>
 import URI from "urijs";
 import moment from "moment";

 export default {
     props: {
         image: {
             type: Object,
             required: true
         }
     },

     computed: {
         indexedOnDisplay() {
             let time = moment(this.image.indexedOn);

             console.log(this.image.indexedOn);

             return `${time.format("LLL")} (${time.fromNow()})`
         }
     },

     methods: {
         constructLinkFromTag(tag) {
             let queryUrl = new URI(window.location);
             queryUrl.setQuery("query", tag);

             return queryUrl.toString();
         },

         searchTag(tag) {
             window.history.pushState({query: tag}, '', this.constructLinkFromTag(tag));
         }
     }
 }
</script>
