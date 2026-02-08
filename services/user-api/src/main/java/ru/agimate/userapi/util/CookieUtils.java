package ru.agimate.userapi.util;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;

public class CookieUtils {
    
    /**
     * Sets an httpOnly cookie with the specified parameters
     * 
     * @param response The HTTP response to add the cookie to
     * @param name The name of the cookie
     * @param value The value of the cookie
     * @param path The path for the cookie
     * @param maxAge The maximum age of the cookie in seconds
     * @param isSecure Whether the cookie should only be sent over HTTPS
     */
    public static void setHttpOnlyCookie(HttpServletResponse response, String name, String value,
                                         String path, int maxAge, boolean isSecure, String domain) {
        Cookie cookie = new Cookie(name, value);
        cookie.setDomain(domain);
        cookie.setHttpOnly(true);
        cookie.setPath(path);
        cookie.setMaxAge(maxAge);
        cookie.setSecure(isSecure);
        response.addCookie(cookie);
    }

    /**
     * Deletes a cookie by name
     *
     * @param response The HTTP response to add the cookie to
     * @param name The name of the cookie to delete
     */
    public static void deleteCookie(HttpServletResponse response, String name) {
        Cookie cookie = new Cookie(name, "");
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }

    public static void deleteCookie(HttpServletResponse response, String name, String domain) {
        Cookie cookie = new Cookie(name, "");
        cookie.setPath("/");
        cookie.setDomain(domain);
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }
}