package ru.agimate.agentworker.workers.run;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.agentworker.GetHistoryResponse;
import ru.agimate.agentworker.HistoryMessage;
import ru.agimate.agentworker.MessageKind;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentRunCoreTest {

    private static HistoryMessage msg(int turnIdx) {
        return HistoryMessage.newBuilder()
                .setTurnIdx(turnIdx)
                .setKind(MessageKind.REQUEST)
                .setMessageJson(ByteString.copyFromUtf8("{}"))
                .build();
    }

    @Test
    @DisplayName("nextTurnIdx is one past the highest turn_idx, not the slice size")
    void nextTurnIdxFromServerIndices() {
        // A session with 60 messages returns the last-50 window (turn_idx 10..59):
        // size() would be 50 and collide with existing rows; the correct next index is 60.
        GetHistoryResponse.Builder resp = GetHistoryResponse.newBuilder();
        for (int i = 10; i <= 59; i++) {
            resp.addMessages(msg(i));
        }
        assertEquals(60, AgentRunCore.nextTurnIdx(resp.build()));
    }

    @Test
    @DisplayName("empty history starts at turn 0")
    void emptyHistory() {
        assertEquals(0, AgentRunCore.nextTurnIdx(GetHistoryResponse.getDefaultInstance()));
    }
}
