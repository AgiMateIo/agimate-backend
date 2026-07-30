package ru.agimate.controlapi.controller.manage.admin;

import lombok.experimental.UtilityClass;

/**
 * The admin-only area of the manage API. Here the path <i>is</i> the gate: the security chain requires
 * {@code ROLE_ADMIN} for everything under {@link #PREFIX}, so controllers of this area carry no
 * {@code @PreAuthorize} of their own. The flip side is that a controller of this package mounted outside
 * the prefix loses the gate without a word — {@code ManageAdminSectionTest} guards exactly that.
 */
@UtilityClass
public class ManageAdminPaths {

    public static final String PREFIX = "/manage/admin";
}
