package ru.agimate.deviceapi.service.dto.board;

import lombok.experimental.UtilityClass;

@UtilityClass
public class BoardEventType {
    public static final String TASK_CREATED = "board.task.created";
    public static final String TASK_STATUS_CHANGED = "board.task.statusChanged";
    public static final String COMMENT_CREATED = "board.task.commentAdded";
}
