/**
 * Yobi, Project Hosting SW
 *
 * Copyright 2012 NAVER Corp.
 * http://yobi.io
 *
 * @Author Yi EungJun
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package utils;

import org.apache.commons.lang3.StringUtils;
import play.api.http.MediaRange;
import play.mvc.Http;

import java.io.UnsupportedEncodingException;
import java.net.*;
import java.util.*;

public class HttpUtil {
    /**
     * Finds the first value by given the key in the given query.
     *
     * This method is used to get a value from a URI query string.
     *
     * @param query
     * @param key
     * @return the value, "" if the query does not have the key.
     */
    public static String getFirstValueFromQuery(Map<String, String[]> query, String key) {
        if (query == null) {
            return "";
        }

        String[] values = query.get(key);

        if (values != null && values.length > 0) {
           return values[0];
        } else {
            return "";
        }
    }

    /**
     * Encodes the filename with RFC 2231; IE 8 or less, and Safari 5 or less
     * are not supported.
     *
     * @param filename
     * @return
     * @throws UnsupportedEncodingException
     * @see http://greenbytes.de/tech/tc2231/
     */
    public static String encodeContentDisposition(String filename)
            throws UnsupportedEncodingException {
        filename = filename.replaceAll("[:\\x5c\\/{?]", "_");
        filename = URLEncoder.encode(filename, "UTF-8").replaceAll("\\+", "%20");
        filename = "filename*=UTF-8''" + filename;
        return filename;
    }

    /**
     * Returns most preferred content-type among supported types.
     *
     * @param request  the request of the client
     * @param types    the supported types
     * @return the most preferred type; {@code null} if the client prefers nothing among the supported types
     */
    public static String getPreferType(Http.Request request, String ... types) {
        // acceptedTypes is sorted by preference.
        for(MediaRange range : request.acceptedTypes()) {
            for(String type : types) {
                if (range.accepts(type)) {
                    return type;
                }
            }
        }
        return null;
    }

    /**
     * Returns whether is the client prefers "application/json" most
     * using getPreferType()
     *
     * @param request
     * @return
     */
    public static Boolean isJSONPreferred(Http.Request request){
        return getPreferType(request, "text/html", "application/json").equals("application/json");
    }

    /**
     * Adds query string made from the given pairs into the given url.
     *
     * The format of the key-value pair is {@code "key=value"}.
     *
     * @param url
     * @param encodedPairs encoded pairs to compose the query string
     * @return the resulting url
     * @throws URISyntaxException
     */
    public static String addQueryString(String url, String ... encodedPairs) throws
            URISyntaxException {
        URI aURI = new URI(url);
        String query = (aURI.getQuery() != null) ? aURI.getQuery() : "";
        query += (query.length() > 0 ? "&" : "") + StringUtils.join(encodedPairs, "&");

        return new URI(aURI.getScheme(), aURI.getAuthority(), aURI.getPath(), query,
                aURI.getFragment()).toString();
    }

    /**
     * Removes key-value pairs from the query string of the given url.
     *
     * The format of the key-value pair is {@code "key=value"}.
     *
     * @param url
     * @param keys keys to be removed; It must not be encoded.
     * @return the resulting url
     * @throws URISyntaxException
     * @throws UnsupportedEncodingException
     */
    public static String removeQueryString(String url, String ... keys) throws
            URISyntaxException, UnsupportedEncodingException {
        URI aURI = new URI(url);

        if (aURI.getQuery() == null) {
            return url;
        }

        List<String> pairStrings = new ArrayList<>();
        Set<String> keySet = new HashSet<>(Arrays.asList(keys));

        for (String pairString : aURI.getQuery().split("&")) {
            String[] pair = pairString.split("=");
            if (pair.length == 0) {
                continue;
            }
            if (!keySet.contains(URLDecoder.decode(pair[0], "UTF-8"))) {
                pairStrings.add(pairString);
            }
        }

        return new URI(aURI.getScheme(), aURI.getAuthority(), aURI.getPath(),
                StringUtils.join(pairStrings, "&"), aURI.getFragment()).toString();
    }

    /**
     * Returns whether request header has "X-Requested-With"
     * and also its value equals to "XMLHttpRequest".
     *
     * Almost of JavaScript framework like as jQuery, prototype, JindoJS,
     * fills "X-Requested-With" header with "XMLHttpRequest" on sending XHR.
     *
     * @param request
     * @return Boolean
     */
    public static Boolean isRequestedWithXHR(Http.Request request){
        String requestedWith = request.getHeader("X-Requested-With");
        return (requestedWith != null && requestedWith.toLowerCase().equals("xmlhttprequest"));
    }

    /**
     * Returns whether {@code request} has "X-PJAX" header
     *
     * @param request
     * @return Boolean
     */
    public static Boolean isPJAXRequest(Http.Request request){
        return Boolean.parseBoolean(request.getHeader("X-PJAX"));
    }
}
