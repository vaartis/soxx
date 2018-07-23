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
                   data-toggle="modal" data-target="#search-help-modal" />
            <div class="modal fade" role="dialog" tabIndex="-1" id="search-help-modal">
                <div class="modal-dialog modal-lg" role="document">
                    <div class="modal-content">
                        <div class="modal-header">
                            <h5 class="modal-title">Search help</h5>
                        </div>
                        <div class="modal-body">
                            Note, that tags without logical operators are implicitly AND'ed.

                            <br><br>

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
                                        <td>
                                            Include a simple tag. This tag cannot contain none of the following symbols: <b>( ) - | &</b> or <b>\</b>.
                                            If you want to use those symbols, you'll need to use the "exact" tag form.
                                        </td>
                                    </tr>
                                    <tr>
                                        <th scope="row">"tag"</th>
                                        <td>
                                            Include an exact tag. This tag can contain any symbols, the <b>"</b> symbol can be
                                            escaped as <b>\"</b>
                                        </td>
                                    </tr>
                                    <tr>
                                        <th scope="row">!tag</th>
                                        <td>
                                            Logically negate any tag, which basically means exclude it by some condition, which can
                                            not only be the full tag, but e.g. a regular expression or a tag group.
                                        </td>
                                    </tr>
                                    <tr>
                                        <th scope="row">REGEX(tag_regex)</th>
                                        <td>
                                            Search tags by a regular expression. Note, that the ")" symbol
                                            can be escaped as \).
                                        </td>
                                    </tr>
                                    <tr>
                                        <th scope="row">(tag1 tag2)</th>
                                        <td>
                                            Tag group. Groups several tags together to make logical operators work on all of them.
                                        </td>
                                    </tr>
                                    <tr>
                                        <th scope="row">tag1 && tag2 <i>and</i> tag1 || tag2 </th>
                                        <td>
                                            Logical operators that combine tags. && means AND, || means OR. Any tag type
                                            can be used with them, including tag groups and excluded tags.
                                        </td>
                                    </tr>
                                </tbody>
                            </table>
                        </div>
                    </div>
                </div>
            </div>
        </form>
    </nav>
</template>

<script>
 import URI from "urijs";
 import urlListener from "url-listener";
 import he from "he";

 Vue.config.productionTip = false

 export default {
     data() {
         return {
             searchString: "",
         };
     },

     mounted() {
         this.updateSearchString();

         urlListener(event => this.updateSearchString());
     },

     methods: {
         updateSearchString() {
             let queryUrl = new URI(window.location);
             if (queryUrl.hasQuery("query"))
                 this.searchString = he.decode(queryUrl.query(true)["query"]);
         },

         doSearch() {
             let queryUrl = new URI(window.location);
             queryUrl.setQuery("query", he.encode(this.searchString, { useNamedReferences: true }));

             // If we are not on the search page, use it's address
             if (queryUrl.path() != "/" && queryUrl.path() != "/index") {
                 queryUrl.path("/index");

                 window.location.href = queryUrl.toString();
             } else {
                 window.history.pushState({query: this.searchString}, '', queryUrl.toString());
             }
         }
     }
 }
</script>
