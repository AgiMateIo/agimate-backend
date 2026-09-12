package ru.agimate.controlapi.database.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * A facet of a skill or a preset that the subject axis ({@link ContentCategory}) cannot carry — who it
 * is for, what kind of work it does, what it needs to run. Tags are a closed vocabulary on purpose: a
 * free-form one grows synonyms and no filter collects them all.
 *
 * <p>A tag is worth adding only when the trait is not in the title or the description — search already
 * finds those — and when a screen filters by it. That is why there is no «paid service» yet: nothing
 * carries it.
 *
 * <p>{@link Group} is presentation only (chips on the frontend); an item's tags are a flat list.
 */
@Getter
public enum ContentTag {

    PERSONAL(Group.AUDIENCE, "Personal"),
    TEAM(Group.AUDIENCE, "For a team"),
    DEVELOPER(Group.AUDIENCE, "For developers"),

    TRACKING(Group.WORK_KIND, "Records and tracking"),
    WRITING(Group.WORK_KIND, "Writing"),
    IMAGES(Group.WORK_KIND, "Images"),
    ADVICE(Group.WORK_KIND, "Advice"),
    MANAGEMENT(Group.WORK_KIND, "Management"),

    VOICE(Group.FEATURE, "Works by voice"),
    PHOTO(Group.FEATURE, "Reads photos"),
    REMINDERS(Group.FEATURE, "Reminds"),
    SCHEDULE(Group.FEATURE, "Runs on a schedule"),
    OWN_TOKEN(Group.FEATURE, "Needs your own token"),
    READ_ONLY(Group.FEATURE, "Reads only, changes nothing");

    /** How the filter is laid out for the user; no meaning in the data. */
    @Getter
    public enum Group {

        AUDIENCE("Audience"),
        WORK_KIND("What it does"),
        FEATURE("Features");

        private final String label;

        Group(String label) {
            this.label = label;
        }
    }

    private final Group group;
    private final String label;

    ContentTag(Group group, String label) {
        this.group = group;
        this.label = label;
    }

    public static String codes() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "));
    }
}
