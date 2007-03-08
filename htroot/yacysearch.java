// yacysearch.java
// -----------------------
// part of the AnomicHTTPD caching proxy
// (C) by Michael Peter Christen; mc@anomic.de
// first published on http://www.anomic.de
// Frankfurt, Germany, 2004
//
// $LastChangedDate$
// $LastChangedRevision$
// $LastChangedBy$
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//
// Using this software in any meaning (reading, learning, copying, compiling,
// running) means that you agree that the Author(s) is (are) not responsible
// for cost, loss of data or any harm that may be caused directly or indirectly
// by usage of this softare or this documentation. The usage of this software
// is on your own risk. The installation and usage (starting/running) of this
// software may allow other people or application to access your computer and
// any attached devices and is highly dependent on the configuration of the
// software which must be done by the user of the software; the author(s) is
// (are) also not responsible for proper configuration and usage of the
// software, even if provoked by documentation provided together with
// the software.
//
// Any changes to this file according to the GPL as documented in the file
// gpl.txt aside this file in the shipment you received can be done to the
// lines that follows this copyright notice here, but changes must not be
// done inside the copyright notive above. A re-distribution must contain
// the intact and unchanged copyright notice.
// Contributions and changes to the program code must be marked as such.
//
// You must compile this file with
// javac -classpath .:../classes yacysearch.java
// if the shell's current path is HTROOT

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.TreeSet;

import de.anomic.htmlFilter.htmlFilterImageEntry;
import de.anomic.http.httpHeader;
import de.anomic.index.indexURLEntry;
import de.anomic.kelondro.kelondroBitfield;
import de.anomic.kelondro.kelondroMSetTools;
import de.anomic.kelondro.kelondroNaturalOrder;
import de.anomic.net.URL;
import de.anomic.plasma.plasmaCondenser;
import de.anomic.plasma.plasmaParserDocument;
import de.anomic.plasma.plasmaSearchImages;
import de.anomic.plasma.plasmaSearchPreOrder;
import de.anomic.plasma.plasmaSearchQuery;
import de.anomic.plasma.plasmaSearchRankingProfile;
import de.anomic.plasma.plasmaSearchTimingProfile;
import de.anomic.plasma.plasmaSwitchboard;
import de.anomic.plasma.plasmaURL;
import de.anomic.plasma.plasmaSearchResults;
import de.anomic.server.serverCore;
import de.anomic.server.serverDate;
import de.anomic.server.serverObjects;
import de.anomic.server.serverSwitch;
import de.anomic.tools.crypt;
import de.anomic.tools.nxTools;
import de.anomic.yacy.yacyCore;
import de.anomic.yacy.yacyNewsPool;
import de.anomic.yacy.yacyNewsRecord;
import de.anomic.yacy.yacySeed;

public class yacysearch {

    public static final int MAX_TOPWORDS = 24;

    public static serverObjects respond(httpHeader header, serverObjects post, serverSwitch env) {
        final plasmaSwitchboard sb = (plasmaSwitchboard) env;

        boolean authenticated = sb.adminAuthenticated(header) >= 2;
        int display = ((post == null) || (!authenticated)) ? 0 : post.getInt("display", 0);
        String promoteSearchPageGreeting = env.getConfig("promoteSearchPageGreeting", "");
        if (promoteSearchPageGreeting.length() == 0) promoteSearchPageGreeting = "P2P WEB SEARCH";

        // case if no values are requested
        final String referer = (String) header.get("Referer");
        String querystring = (post == null) ? "" : post.get("search", "").trim();
        
        if ((post == null) || (env == null) || (querystring.length() == 0)) {

            // save referrer
            // System.out.println("HEADER=" + header.toString());
            if (referer != null) {
                URL url;
                try { url = new URL(referer); } catch (MalformedURLException e) { url = null; }
                if ((url != null) && (serverCore.isNotLocal(url))) {
                    final HashMap referrerprop = new HashMap();
                    referrerprop.put("count", "1");
                    referrerprop.put("clientip", header.get("CLIENTIP"));
                    referrerprop.put("useragent", header.get("User-Agent"));
                    referrerprop.put("date", (new serverDate()).toShortString(false));
                    if (sb.facilityDB != null) try { sb.facilityDB.update("backlinks", referer, referrerprop); } catch (IOException e) {}
                }
            }

            // we create empty entries for template strings
            final serverObjects prop = new serverObjects();
            prop.putASIS("promoteSearchPageGreeting", promoteSearchPageGreeting);
            prop.put("former", "");
            prop.put("count", 10);
            prop.put("order", plasmaSearchPreOrder.canUseYBR() ? "YBR-Date-Quality" : "Date-Quality-YBR");
            prop.put("resource", "global");
            prop.put("time", 6);
            prop.put("urlmaskfilter", ".*");
            prop.put("prefermaskfilter", "");
            prop.put("indexof", "off");
            prop.put("constraint", plasmaSearchQuery.catchall_constraint.exportB64());
            prop.put("cat", "href");
            prop.put("depth", "0");
            prop.put("type", 0);
            prop.put("type_excluded", 0);
            prop.put("type_num-results", 0);
            prop.put("type_combine", 0);
            prop.put("type_resultbottomline", 0);
            prop.put("type_results", "");
            prop.put("display", display);
            prop.put("contentdom", "text");
            prop.put("contentdomCheckText", 1);
            prop.put("contentdomCheckAudio", 0);
            prop.put("contentdomCheckVideo", 0);
            prop.put("contentdomCheckImage", 0);
            prop.put("contentdomCheckApp", 0);
            return prop;
        }

        // collect search attributes
        int maxDistance = Integer.MAX_VALUE;
        
        if ((querystring.length() > 2) && (querystring.charAt(0) == '"') && (querystring.charAt(querystring.length() - 1) == '"')) {
            querystring = querystring.substring(1, querystring.length() - 1).trim();
            maxDistance = 1;
        }
        if (sb.facilityDB != null) try { sb.facilityDB.update("zeitgeist", querystring, post); } catch (Exception e) {}

        int count = Integer.parseInt(post.get("count", "10"));
        final String order = post.get("order", plasmaSearchPreOrder.canUseYBR() ? "YBR-Date-Quality" : "Date-Quality-YBR");
        boolean global = (post == null) ? true : post.get("resource", "global").equals("global");
        final boolean indexof = post.get("indexof","").equals("on"); 
        final long searchtime = 1000 * Long.parseLong(post.get("time", "10"));
        String urlmask = "";
        if (post.containsKey("urlmask") && post.get("urlmask").equals("no")) {
            urlmask = ".*";
        } else {
            urlmask = (post.containsKey("urlmaskfilter")) ? (String) post.get("urlmaskfilter") : ".*";
        }
        String prefermask = post.get("prefermaskfilter", "");
        if ((prefermask.length() > 0) && (prefermask.indexOf(".*") < 0)) prefermask = ".*" + prefermask + ".*";

        kelondroBitfield constraint = post.containsKey("constraint") ? new kelondroBitfield(4, post.get("constraint", "______")) : plasmaSearchQuery.catchall_constraint;
        if (indexof) {
            constraint = new kelondroBitfield();
            constraint.set(plasmaCondenser.flag_cat_indexof, true);
        }
        
        // SEARCH
        final boolean indexDistributeGranted = sb.getConfig("allowDistributeIndex", "true").equals("true");
        final boolean indexReceiveGranted = sb.getConfig("allowReceiveIndex", "true").equals("true");
        if (!indexDistributeGranted || !indexReceiveGranted) { global = false; }
        
        // find search domain
        int contentdomCode = plasmaSearchQuery.CONTENTDOM_TEXT;
        String contentdomString = post.get("contentdom", "text");
        if (contentdomString.equals("text")) contentdomCode = plasmaSearchQuery.CONTENTDOM_TEXT;
        if (contentdomString.equals("audio")) contentdomCode = plasmaSearchQuery.CONTENTDOM_AUDIO;
        if (contentdomString.equals("video")) contentdomCode = plasmaSearchQuery.CONTENTDOM_VIDEO;
        if (contentdomString.equals("image")) contentdomCode = plasmaSearchQuery.CONTENTDOM_IMAGE;
        if (contentdomString.equals("app")) contentdomCode = plasmaSearchQuery.CONTENTDOM_APP;
        
        // patch until better search profiles are available
        if ((contentdomCode != plasmaSearchQuery.CONTENTDOM_TEXT) && (count <= 10)) count = 30;
        
        serverObjects prop = new serverObjects();
        if (post.get("cat", "href").equals("href")) {

        final TreeSet query = plasmaSearchQuery.cleanQuery(querystring);
        // filter out stopwords
        final TreeSet filtered = kelondroMSetTools.joinConstructive(query, plasmaSwitchboard.stopwords);
        if (filtered.size() > 0) {
            kelondroMSetTools.excludeDestructive(query, plasmaSwitchboard.stopwords);
        }

        // if a minus-button was hit, remove a special reference first
        if (post.containsKey("deleteref")) {
            if (!sb.verifyAuthentication(header, true)) {
                prop.put("AUTHENTICATE", "admin log-in"); // force log-in
                return prop;
            }
                
            // delete the index entry locally
            final String delHash = post.get("deleteref", ""); // urlhash
            sb.wordIndex.removeReferences(query, delHash);

            // make new news message with negative voting
            HashMap map = new HashMap();
            map.put("urlhash", delHash);
            map.put("vote", "negative");
            map.put("refid", "");
            yacyCore.newsPool.publishMyNews(new yacyNewsRecord("stippavt", map));
        }

        // if aplus-button was hit, create new voting message
        if (post.containsKey("recommendref")) {
            if (!sb.verifyAuthentication(header, true)) {
                prop.put("AUTHENTICATE", "admin log-in"); // force log-in
                return prop;
            }
            final String recommendHash = post.get("recommendref", ""); // urlhash
            indexURLEntry urlentry = sb.wordIndex.loadedURL.load(recommendHash, null);
            if (urlentry != null) {
                indexURLEntry.Components comp = urlentry.comp();
                plasmaParserDocument document;
                document = sb.snippetCache.retrieveDocument(comp.url(), true, 5000, true);
                if (document != null) {
                    // create a news message
                    HashMap map = new HashMap();
                    map.put("url", comp.url().toNormalform().replace(',', '|'));
                    map.put("title", comp.descr().replace(',', ' '));
                    map.put("description", ((document == null) ? comp.descr() : document.getMainLongTitle()).replace(',', ' '));
                    map.put("tags",  ((document == null) ? "" : document.getKeywords(' ')));
                    yacyCore.newsPool.publishMyNews(new yacyNewsRecord("stippadd", map));
                    document.close();
                }
            }
        }

        // prepare search properties
        final boolean yacyonline = ((yacyCore.seedDB != null) && (yacyCore.seedDB.mySeed != null) && (yacyCore.seedDB.mySeed.getAddress() != null));
        final boolean samesearch = env.getConfig("last-search", "").equals(querystring + contentdomString);
        final boolean globalsearch = (global) && (yacyonline) && (!samesearch);
        
        // do the search
        plasmaSearchQuery thisSearch = new plasmaSearchQuery(
                    query,
                    maxDistance,
                    prefermask,
                    contentdomCode,
                    count,
                    searchtime,
                    urlmask,
                    (globalsearch) ? plasmaSearchQuery.SEARCHDOM_GLOBALDHT : plasmaSearchQuery.SEARCHDOM_LOCAL,
                    "",
                    20,
                    constraint);
        plasmaSearchRankingProfile ranking = (sb.getConfig("rankingProfile", "").length() == 0) ? new plasmaSearchRankingProfile(contentdomString) : new plasmaSearchRankingProfile("", crypt.simpleDecode(sb.getConfig("rankingProfile", ""), null));
        plasmaSearchTimingProfile localTiming = new plasmaSearchTimingProfile(4 * thisSearch.maximumTime / 10, thisSearch.wantedResults);
        plasmaSearchTimingProfile remoteTiming = new plasmaSearchTimingProfile(6 * thisSearch.maximumTime / 10, thisSearch.wantedResults);
        
        plasmaSearchResults results = sb.searchFromLocal(thisSearch, ranking, localTiming, remoteTiming, true, (String) header.get("CLIENTIP"));
        //prop=sb.searchFromLocal(thisSearch, ranking, localTiming, remoteTiming, true, (String) header.get("CLIENTIP"));
        prop=new serverObjects();
        //prop.put("references", 0);
        URL wordURL=null;
        prop.put("num-results_totalcount", results.getTotalcount());
        prop.put("num-results_filteredcount", results.getFilteredcount());
        prop.put("num-results_orderedcount", results.getOrderedcount());
        prop.put("num-results_linkcount", results.getLinkcount());
        prop.put("type_results", 0);
        if(results.numResults()!=0){
            //we've got results
            prop.put("num-results_totalcount", results.getTotalcount());
            prop.put("num-results_filteredcount", results.getFilteredcount());
            prop.put("num-results_orderedcount", Integer.toString(results.getOrderedcount())); //why toString?
            prop.put("num-results_globalresults", results.getGlobalresults());
            for(int i=0;i<results.numResults();i++){
                plasmaSearchResults.searchResult result=results.getResult(i);
                try {
                    prop.put("type_results_" + i + "_authorized_recommend", (yacyCore.newsPool.getSpecific(yacyNewsPool.OUTGOING_DB, "stippadd", "url", result.getUrl()) == null) ? 1 : 0);
                } catch (IOException e) {}
                //prop.put("type_results_" + i + "_authorized_recommend_deletelink", "/yacysearch.html?search=" + results.getFormerSearch() + "&amp;Enter=Search&amp;count=" + results.getQuery().wantedResults + "&amp;order=" + crypt.simpleEncode(results.getRanking().toExternalString()) + "&amp;resource=local&amp;time=3&amp;deleteref=" + result.getUrlhash() + "&amp;urlmaskfilter=.*");
                //prop.put("type_results_" + i + "_authorized_recommend_recommendlink", "/yacysearch.html?search=" + results.getFormerSearch() + "&amp;Enter=Search&amp;count=" + results.getQuery().wantedResults + "&amp;order=" + crypt.simpleEncode(results.getRanking().toExternalString()) + "&amp;resource=local&amp;time=3&amp;recommendref=" + result.getUrlhash() + "&amp;urlmaskfilter=.*");
                prop.put("type_results_" + i + "_authorized_recommend_deletelink", "/yacysearch.html?search=" + results.getFormerSearch() + "&Enter=Search&count=" + results.getQuery().wantedResults + "&order=" + crypt.simpleEncode(results.getRanking().toExternalString()) + "&resource=local&time=3&deleteref=" + result.getUrlhash() + "&urlmaskfilter=.*");
                prop.put("type_results_" + i + "_authorized_recommend_recommendlink", "/yacysearch.html?search=" + results.getFormerSearch() + "&Enter=Search&count=" + results.getQuery().wantedResults + "&order=" + crypt.simpleEncode(results.getRanking().toExternalString()) + "&resource=local&time=3&recommendref=" + result.getUrlhash() + "&urlmaskfilter=.*");
                prop.put("type_results_" + i + "_authorized_urlhash", result.getUrlhash());
                prop.put("type_results_" + i + "_description", result.getUrlentry().comp().descr());
                prop.put("type_results_" + i + "_url", result.getUrl());
                prop.put("type_results_" + i + "_urlhash", result.getUrlhash());
                prop.put("type_results_" + i + "_urlhexhash", yacySeed.b64Hash2hexHash(result.getUrlhash()));
                prop.put("type_results_" + i + "_urlname", nxTools.shortenURLString(result.getUrlname(), 120));
                prop.put("type_results_" + i + "_date", plasmaSwitchboard.dateString(result.getUrlentry().moddate()));
                prop.put("type_results_" + i + "_ybr", plasmaSearchPreOrder.ybr(result.getUrlentry().hash()));
                prop.put("type_results_" + i + "_size", Long.toString(result.getUrlentry().size()));
                try {
                    prop.put("type_results_" + i + "_words", URLEncoder.encode(results.getQuery().queryWords.toString(),"UTF-8"));
                } catch (UnsupportedEncodingException e) {}
                prop.put("type_results_" + i + "_former", results.getFormerSearch());
                prop.put("type_results_" + i + "_rankingprops", result.getUrlentry().word().toPropertyForm() + ", domLengthEstimated=" + plasmaURL.domLengthEstimation(result.getUrlhash()) +
                        ((plasmaURL.probablyRootURL(result.getUrlhash())) ? ", probablyRootURL" : "") + 
                        (((wordURL = plasmaURL.probablyWordURL(result.getUrlhash(), results.getQuery().words(""))) != null) ? ", probablyWordURL=" + wordURL.toNormalform() : ""));
                // adding snippet if available
                if (result.hasSnippet()) {
                    prop.put("type_results_" + i + "_snippet", 1);
                    prop.putASIS("type_results_" + i + "_snippet_text", result.getSnippet().getLineMarked(results.getQuery().queryHashes));//FIXME: the ASIS should not be needed, if there is no html in .java
                } else {
                    prop.put("type_results_" + i + "_snippet", 0);
                    prop.put("type_results_" + i + "_snippet_text", "");
                }
                prop.put("type_results", results.numResults());
                prop.put("references", results.getReferences());
                prop.put("num-results_linkcount", Integer.toString(results.numResults()));
            }
        }

        // remember the last search expression
        env.setConfig("last-search", querystring + contentdomString);

        // process result of search
        prop.put("type_resultbottomline", 0);
        if (filtered.size() > 0) {
            prop.put("excluded", 1);
            prop.put("excluded_stopwords", filtered.toString());
        } else {
            prop.put("excluded", 0);
        }

            if (prop == null || prop.size() == 0) {
                if (post.get("search", "").length() < 3) {
                    prop.put("num-results", 2); // no results - at least 3 chars
                } else {
                    prop.put("num-results", 1); // no results
                }
            } else {
                final int totalcount = prop.getInt("num-results_totalcount", 0);
                if (totalcount >= 10) {
                    final Object[] references = (Object[]) prop.get( "references", new String[0]);
                    prop.put("num-results", 4);
                    int hintcount = references.length;
                    if (hintcount > 0) {

                        prop.put("type_combine", 1);
                        // get the topwords
                        final TreeSet topwords = new TreeSet(kelondroNaturalOrder.naturalOrder);
                        String tmp = "";
                        for (int i = 0; i < hintcount; i++) {
                            tmp = (String) references[i];
                            if (tmp.matches("[a-z]+")) {
                                topwords.add(tmp);
                            // } else {
                            //    topwords.add("(" + tmp + ")");
                            }
                        }

                        // filter out the badwords
                        final TreeSet filteredtopwords = kelondroMSetTools.joinConstructive(topwords, plasmaSwitchboard.badwords);
                        if (filteredtopwords.size() > 0) {
                            kelondroMSetTools.excludeDestructive(topwords, plasmaSwitchboard.badwords);
                        }

						//avoid stopwords being topwords
                        if (env.getConfig("filterOutStopwordsFromTopwords", "true").equals("true")) {
                        if ((plasmaSwitchboard.stopwords != null) && (plasmaSwitchboard.stopwords.size() > 0)) {
                            kelondroMSetTools.excludeDestructive(topwords, plasmaSwitchboard.stopwords);
                        	}
                        }
						
                        String word;
                        hintcount = 0;
                        final Iterator iter = topwords.iterator();
                        while (iter.hasNext()) {
                            word = (String) iter.next();
                            if (word != null) {
                                prop.put("type_combine_words_" + hintcount + "_word", word);
                                prop.put("type_combine_words_" + hintcount + "_newsearch", post.get("search", "").replace(' ', '+') + "+" + word);
                                prop.put("type_combine_words_" + hintcount + "_count", count);
                                prop.put("type_combine_words_" + hintcount + "_order", order);
                                prop.put("type_combine_words_" + hintcount + "_resource", ((global) ? "global" : "local"));
                                prop.put("type_combine_words_" + hintcount + "_time", (searchtime / 1000));
                            }
                            prop.put("type_combine_words", hintcount);
                            if (hintcount++ > MAX_TOPWORDS) {
                                break;
                            }
                        }
                    }
                } else {
                    if (totalcount == 0) {
                        prop.put("num-results", 3); // long
                    } else {
                        prop.put("num-results", 4);
                    }
                }
            }

            if (yacyonline) {
                if (global) {
                    prop.put("type_resultbottomline", 1);
                    prop.put("type_resultbottomline_globalresults", prop.get("num-results_globalresults", "0"));
                } else {
                    prop.put("type_resultbottomline", 2);
                }
            } else {
                if (global) {
                    prop.put("type_resultbottomline", 3);
                } else {
                    prop.put("type_resultbottomline", 4);
                }
            }

            prop.put("type", (thisSearch.contentdom == plasmaSearchQuery.CONTENTDOM_TEXT) ? 0 : ((thisSearch.contentdom == plasmaSearchQuery.CONTENTDOM_IMAGE) ? 2 : 1));
            if (prop.getInt("type", 0) == 1) prop.put("type_mediatype", contentdomString);
            prop.put("cat", "href");
            prop.put("depth", "0");

            // adding some additional properties needed for the rss feed
            String hostName = (String) header.get("Host", "localhost");
            if (hostName.indexOf(":") == -1) hostName += ":" + serverCore.getPortNr(env.getConfig("port", "8080"));
            prop.put("rssYacyImageURL", "http://" + hostName + "/env/grafics/yacy.gif");

        }

        if (post.get("cat", "href").equals("image")) {

            int depth = post.getInt("depth", 0);
            int columns = post.getInt("columns", 6);
            URL url = null;
            try {url = new URL(post.get("url", ""));} catch (MalformedURLException e) {}
            plasmaSearchImages si = new plasmaSearchImages(sb.snippetCache, 6000, url, depth);
            Iterator i = si.entries();
            htmlFilterImageEntry ie;
            int line = 0;
            while (i.hasNext()) {
                int col = 0;
                for (col = 0; col < columns; col++) {
                    if (!i.hasNext()) break;
                    ie = (htmlFilterImageEntry) i.next();
                    String urls = ie.url().toString();
                    String name = "";
                    int p = urls.lastIndexOf('/');
                    if (p > 0) name = urls.substring(p + 1);
                    prop.put("type_results_" + line + "_line_" + col + "_url", urls);
                    prop.put("type_results_" + line + "_line_" + col + "_name", name);
                }
                prop.put("type_results_" + line + "_line", col);
                line++;
            }
            prop.put("type_results", line);

            prop.put("type", 3); // set type of result: image list
            prop.put("cat", "href");
            prop.put("depth", depth);
        }

        // if user is not authenticated, he may not vote for URLs
        int linkcount = Integer.parseInt(prop.get("num-results_linkcount", "0"));
        for (int i=0; i<linkcount; i++)
            prop.put("type_results_" + i + "_authorized", (authenticated) ? 1 : 0);

        prop.putASIS("promoteSearchPageGreeting", promoteSearchPageGreeting);
        prop.put("former", post.get("search", ""));
        prop.put("count", count);
        prop.put("order", order);
        prop.put("resource", (global) ? "global" : "local");
        prop.put("time", searchtime / 1000);
        prop.put("urlmaskfilter", urlmask);
        prop.put("prefermaskfilter", prefermask);
        prop.put("display", display);
        prop.put("indexof", (indexof) ? "on" : "off");
        prop.put("constraint", constraint.exportB64());
        prop.put("contentdom", contentdomString);
        prop.put("contentdomCheckText", (contentdomCode == plasmaSearchQuery.CONTENTDOM_TEXT) ? 1 : 0);
        prop.put("contentdomCheckAudio", (contentdomCode == plasmaSearchQuery.CONTENTDOM_AUDIO) ? 1 : 0);
        prop.put("contentdomCheckVideo", (contentdomCode == plasmaSearchQuery.CONTENTDOM_VIDEO) ? 1 : 0);
        prop.put("contentdomCheckImage", (contentdomCode == plasmaSearchQuery.CONTENTDOM_IMAGE) ? 1 : 0);
        prop.put("contentdomCheckApp", (contentdomCode == plasmaSearchQuery.CONTENTDOM_APP) ? 1 : 0);

        // return rewrite properties
        return prop;
    }

}
