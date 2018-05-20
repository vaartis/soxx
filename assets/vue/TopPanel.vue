<template>
    <nav class="navbar navbar-expand-lg navbar-light bg-light">
        <a class="navbar-brand" href="#">Soxx</a>
        <div class="collapse navbar-collapse">
            <div class="navbar-nav">
            </div>
        </div>
        <form class="form-inline w-25">
            <input class="w-50" type="search" placeholder="Search..." v-model="searchString" />
            <input class="btn btn-primary form-control mx-2" type="button" value="Search" />
            <input class="btn btn-light form-control mx-2"
                   type="button" value="Help"
                   data-toggle="collapse" data-target="#search-help-content" />
            <div class="collapse" id="search-help-content">
                <table class="table">
                    <thead>
                        <tr>
                            <th scope="col">Syntax</th>
                            <th scope="col">Meaning</th>
                            <th scope="col">Example</th>
                        </tr>
                    </thead>
                </table>
            </div>
        </form>
    </nav>
</template>

<script>
 import URI from "urijs";

 Vue.config.productionTip = false

 export default {
     data() {
         return {
             searchString: "",
         };
     },

     mounted() {
         let queryUrl = new URI(window.location);
         if (queryUrl.hasQuery("query"))
             this.searchString = queryUrl.query(true)["query"];
     }
 }
</script>
