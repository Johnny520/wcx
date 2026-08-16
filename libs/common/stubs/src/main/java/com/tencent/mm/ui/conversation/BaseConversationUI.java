package com.tencent.mm.ui.conversation;

import android.app.Activity;

import com.tencent.mm.ui.MMFragment;

public class BaseConversationUI extends Activity {

    public BaseConversationFmUI conversationFm;

    public void setTitle(String str) {
        throw new RuntimeException("Stub!");
    }

    public static class BaseConversationFmUI extends MMFragment {

    }
}
