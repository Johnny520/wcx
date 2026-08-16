package com.tencent.mm.opensdk.modelmsg;

import java.util.HashMap;

public class WXMiniProgramObject implements WXMediaMessage.IMediaObject {

    public String path;
    public String userName;
    public String webpageUrl;
    public boolean withShareTicket;
    public int miniprogramType = 0;
    public int disableforward = 0;
    public boolean isUpdatableMessage = false;
    public boolean isSecretMessage = false;
    private HashMap<String, String> extraInfoMap = null;
}
