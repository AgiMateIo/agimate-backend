package ru.agimate.userapi.util;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;

public class CookieUtils {
    
    /**
     * Single place where the platform's cookies are built, so HttpOnly cannot be forgotten on one
     * of them. {@code isSecure} comes from {@code app.oauth.cookie-secure}.
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