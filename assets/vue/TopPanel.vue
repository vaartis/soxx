<template>
    <nav class="navbar navbar-expand-lg navbar-light bg-light">
        <a class="navbar-brand" href="/">Soxx</a>
        <div class="collapse navbar-collapse">
            <div class="navbar-nav">
            </div>
        </div>
        <form class="form-inline w-25" v-on:submit.prevent="doSearch">
            <input class="w-50" type="search" placeholder="Search..."
                   v-model="searchString" />
            <input class="btn btn-primary form-control mx-2" type="submit"
                   value="Search" />
            <input class="btn btn-light form-control mx-2"
                   type="button" value="Help"
                   data-toggle="collapse" data-target="#search-help-content" />
            <div class="collapse mt-3" id="search-help-content">
                <table class="table">
                    <thead>
                        <tr>
                            <th scope="col">Syntax</th>
                            <th scope="col">Meaning</th>
                        </tr>
                    </thead>
                    <tbody>
                        <tr>
                            <th scope="row">tag</th>
                            <td>Include the tag</td>
                        </tr>
                        <tr>
                            <th scope="row">-tag</th>
                            <td>Exclude the tag</td>
                        </tr>
                        <tr>
                            <th scope="row">regex~tag_regex~</th>
                            <td>
                                Search tags by a regular expression.
                                Note, that the regular expression <b>cannot</b> contant
                                a tilde (that would end the tag)
                            </td>
                        </tr>
                    </tbody>
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
     },

     methods: {
         doSearch() {
             let queryUrl = new URI(window.location);
             queryUrl.setQuery("query", this.searchString);

             window.history.pushState({query: this.searchString}, '', queryUrl.toString());
         }
     }
 }
</script>
