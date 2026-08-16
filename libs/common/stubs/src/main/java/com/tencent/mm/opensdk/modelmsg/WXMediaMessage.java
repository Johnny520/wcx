package com.tencent.mm.opensdk.modelmsg;

public class WXMediaMessage {

    public IMediaObject mediaObject;
    public String title;
    public String description;
    public byte[] thumbData;

    public interface IMediaObject {}
}
