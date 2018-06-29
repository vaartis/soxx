<template>
    <div class="card-deck" id="imboard-deck">
        <div class="card" v-for="imboard in imboards">
            <div class="card-body">
                <h5 class="card-title">{{ imboard._id }}</h5>
                <table class="table">
                    <tbody>
                        <tr>
                            <th scope="row">Reported image count</th>
                            <td>{{ imboard.reportedImageCount }}</td>
                        </tr>
                        <tr>
                            <th scope="row">Images indexed</th>
                            <td>{{ imboard.indexedImageCount }}</td>
                        </tr>
                        <tr>
                            <th scope="row">Images downloaded</th>
                            <td>{{ imboard.downloadedImageCount}}</td>
                        </tr>

                        <tr>
                            <th scope="row">Page size</th>
                            <td>{{ imboard.pageSize }}</td>
                        </tr>
                    </tbody>
                </table>

                <div v-if="imboard.reportedImageCount && imboard.indexedImageCount" class="card-index-progressbar">
                    <b>{{ findPercent(imboard.reportedImageCount, imagesToPages(imboard.indexedImageCount, imboard.pageSize)).toFixed(2) }}%</b>
                    <div class="progress" >
                        <div class="progress-bar" role="progressbar"
                             v-bind:style="{width: findPercent(imboard.reportedImageCount, imagesToPages(imboard.indexedImageCount, imboard.pageSize)) + '%'}">
                        </div>
                    </div>
                </div>

                <div v-if="imboard.indexedImageCount && imboard.downloadedImageCount" class="card-progressbar">
                    <b>{{ findPercent(imboard.indexedImageCount, imboard.downloadedImageCount).toFixed(2) }}%</b>
                    <div class="progress" >
                        <div class="progress-bar bg-warning" role="progressbar"
                             v-bind:style="{width: findPercent(imboard.indexedImageCount, imboard.downloadedImageCount) + '%'}">
                        </div>
                    </div>
                </div>


                <button class="btn btn-primary"
                        v-if="scrapperStatus[imboard._id] ? !scrapperStatus[imboard._id].isIndexing : false"
                        v-on:click="scrapperAction(imboard._id, 'start-indexing')">
                    Start indexing
                </button>
                <button class="btn btn-danger"
                        v-else v-on:click="scrapperAction(imboard._id, 'stop-indexing')">
                    Stop indexing
                </button>

                <button class="btn btn-warning"
                        v-if="scrapperStatus[imboard._id] ? !scrapperStatus[imboard._id].isDownloading : false"
                        v-on:click="scrapperAction(imboard._id, 'start-downloading')">
                    Start downloading
                </button>
                <button class="btn btn-danger"
                        v-else v-on:click="scrapperAction(imboard._id, 'stop-downloading')">
                    Stop downloading
                </button>
            </div>
        </div>
    </div>
</template>

<script>
 import _ from "lodash";

 Vue.config.productionTip = false

 let ws = new WebSocket(`ws://${window.location.host}/api/v1/admin_panel_socket`);

 export default {
     data() {
         return {
             imboards: [],
             scrapperStatus: {},
         };
     },

     mounted() {
         ws.onmessage = (msg) => {
             let msgData = JSON.parse(msg.data);
             switch (msgData.tp) {
                 case "image-counters-updated":
                     let ind = _.findIndex(this.imboards, {_id: msgData.imboard});

                     Vue.set(
                         this.imboards,
                         ind,
                         _.merge(this.imboards[ind], msgData.value)
                     );
                     break;
                 case "imboard-updated":
                     Vue.set(
                         this.imboards,
                         _.findIndex(this.imboards, {_id: msgData.value._id}),
                         msgData.value
                     );
                     break;
                 case "imboard-deleted":
                     this.imboards.splice(_.findIndex(this.imboards, {_id: msgData.value}), 1)
                     break;
                 case "imboard-scrapper-status":
                     Vue.set(this.scrapperStatus, msgData.imboard, msgData.value);
             }
         };

         fetch("/api/v1/imboard_info").then(res => {
             res.json().then(imboard_list => {
                 this.imboards = imboard_list;

                 _.map(imboard_list, (imboard) => {
                     ws.send(JSON.stringify({tp: "sub-to-image-counters", imboard: imboard._id}))
                     ws.send(JSON.stringify({tp: "imboard-scrapper-status", imboard: imboard._id}))
                 });
             });
         });

     },

     methods: {
         scrapperAction(boardName, action) {
             ws.send(JSON.stringify({
                 tp: "imboard-scrapper-action",
                 imboard: boardName,
                 action: action
             }));
         },

         imagesToPages(imgCount, perPage) {
             return Math.round(imgCount / perPage);
         },

         findPercent(all, current) {
             return (current / all) * 100;
         }
     }
 }
</script>

<style>
 #imboard-deck {
     max-width: 100%;
     margin: 2%;
 }

 .card-index-progressbar {
     margin: 2%;
 }

</style>
