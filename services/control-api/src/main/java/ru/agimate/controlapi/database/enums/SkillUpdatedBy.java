package ru.agimate.controlapi.database.enums;

/** Who last changed a skill's content: the app that brought it, or the user. */
public enum SkillUpdatedBy {

    /** The text is the app's declaration; the app may refresh it at the next link. */
    APP,

    /** The user edited the skill; the app no longer overwrites it. */
    USER
}
