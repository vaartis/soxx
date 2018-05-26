<template>
    <div>
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
        <a v-bind:href="imageSrc">
            <img class="img-fluid" v-bind:src="imageSrc" />
        </a>
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
         },

         isADedicatedPage: {
             type: Boolean,
             required: true
         }
     },

     computed: {
         indexedOnDisplay() {
             let time = moment(this.image.indexedOn);

             return `${time.format("LLL")} (${time.fromNow()})`
         },

         imageSrc() {
             // Check if undefined
             if (this.image.from) {
                 return this.image.from[0].image;
             }
         }
     },

     methods: {
         constructLinkFromTag(tag) {
             // This uses the current URI, but when we're on a separate page,
             // we need to use /index

             let baseURL = this.isADedicatedPage ? "/index" : window.location.href;

             let queryUrl = new URI(baseURL);
             queryUrl.setQuery("query", tag);

             return queryUrl.toString();
         },

         searchTag(tag) {
             if (this.isADeicatedPage) {
                 window.location.href = this.constructLinkFromTag(tag);
             } else {
                 window.history.pushState({query: tag}, '', this.constructLinkFromTag(tag));
             }
         }
     }
 }
</script>