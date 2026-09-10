package ru.agimate.controlapi.database.projections;

import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;

/**
 * One line of a dialogue as the daily note request sees it — narrower than the entity on purpose:
 * an INBOUND row also carries {@code trigger_input}, the channel event in full, which a note needs
 * none of.
 */
public interface SessionNoteLineProjection {
    ChannelSessionMessageKind getKind();
    String getMessage();
}
