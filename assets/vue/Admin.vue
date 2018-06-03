<template>
    <div class="card-deck" id="imboard-deck">
        <div class="card" v-for="imboard in imboards">
            <div class="card-body">
                <h5 class="card-title">{{ imboard._id }}</h5>
                <table class="table">
                    <tbody>
                        <tr>
                            <th scope="row">Estimate page count</th>
                            <td>{{ imboard.estimatePages }}</td>
                        </tr>
                        <tr>
                            <th scope="row">Last page indexed</th>
                            <td>{{ imboard.lastIndexedPage }}</td>
                        </tr>
                    </tbody>
                </table>

                <div v-if="imboard.estimatePages && imboard.lastIndexedPage" class="card-index-progressbar">
                    <b>{{ ((imboard.lastIndexedPage / imboard.estimatePages) * 100).toFixed(2) }}%</b>
                    <div class="progress" >
                        <div class="progress-bar" role="progressbar"
                             v-bind:style="progressBarWidth(imboard.estimatePages, imboard.lastIndexedPage)">
                        </div>
                    </div>
                </div>

                <button class="btn btn-primary"
                        v-if="!isIndexing[imboard._id]" v-on:click="indexingAction(imboard._id, 'start')">
                    Start indexing
                </button>
                <button class="btn btn-danger"
                        v-else v-on:click="indexingAction(imboard._id, 'stop')">
                    Stop indexing
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
             isIndexing: {},
         };
     },

     mounted() {
         ws.onmessage = (msg) => {
             let msgData = JSON.parse(msg.data);
             switch (msgData.tp) {
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
                 case "imboard-is-indexing":
                     Vue.set(this.isIndexing, msgData.imboard, msgData.value);

                     this.isIndexing[msgData.imboard] = msgData.value;
             }
         };

         fetch("/api/v1/imboard_info").then(res => {
             res.json().then(imboard_list => {
                 this.imboards = imboard_list;

                 _.map(imboard_list, (imboard) => {
                     ws.send(JSON.stringify({tp: "imboard-is-indexing", imboard: imboard._id}))
                 });
             });
         });

     },

     methods: {
         indexingAction(boardName, action) {
             ws.send(JSON.stringify({
                 tp: "imboard-indexing-action",
                 imboard: boardName,
                 action: action
             }));
         },

         progressBarWidth(allPages, currentPage) {
             return {width: ((currentPage / allPages) * 100) + "%"};
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
