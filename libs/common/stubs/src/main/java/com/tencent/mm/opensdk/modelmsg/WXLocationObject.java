package com.tencent.mm.opensdk.modelmsg;

public class WXLocationObject implements WXMediaMessage.IMediaObject {

    public double lat;
    public double lng;

    public WXLocationObject() {
        this(0.0d, 0.0d);
    }

    public WXLocationObject(double d16, double d17) {
        this.lat = d16;
        this.lng = d17;
    }
}
