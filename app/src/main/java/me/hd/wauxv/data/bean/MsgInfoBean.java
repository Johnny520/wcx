package me.hd.wauxv.data.bean;

public class MsgInfoBean {
    public String xml = "";
    public String sender = "";
    public String senderId = "";
    public String sendTalker = "";
    public String talker = "";
    public String talkerId = "";
    public String content = "";
    public String text = "";
    public long msgId = 0L;
    public String msgType = "";
    public String type = "";
    public long createTime = 0L;
    public long msgSvrId = 0L;
    public String msgSource = "";
    public String selfWxId = "";
    public String source = "";
    public String kind = "";
    public String nativeUrl = "";

    public static class ImageMsg {
        public String md5 = "";
        public String bigImgUrl = "";
        public String midImgUrl = "";
        public String thumbUrl = "";
        public String key = "";
        public int bigLength = 0;
        public int midLength = 0;
        public int thumbLength = 0;

        public ImageMsg() {
        }

        public ImageMsg(String md5, String bigImgUrl, String midImgUrl, String thumbUrl, String key) {
            this(md5, bigImgUrl, midImgUrl, thumbUrl, key, 0, 0, 0);
        }

        public ImageMsg(String md5, String bigImgUrl, String midImgUrl, String thumbUrl,
                        String key, int bigLength, int midLength, int thumbLength) {
            this.md5 = md5 != null ? md5 : "";
            this.bigImgUrl = bigImgUrl != null ? bigImgUrl : "";
            this.midImgUrl = midImgUrl != null ? midImgUrl : "";
            this.thumbUrl = thumbUrl != null ? thumbUrl : "";
            this.key = key != null ? key : "";
            this.bigLength = Math.max(0, bigLength);
            this.midLength = Math.max(0, midLength);
            this.thumbLength = Math.max(0, thumbLength);
        }

        public String getMd5() {
            return md5;
        }

        public String getBigImgUrl() {
            return bigImgUrl;
        }

        public String getMidImgUrl() {
            return midImgUrl;
        }

        public String getThumbUrl() {
            return thumbUrl;
        }

        public String getCdnUrl() {
            if (thumbUrl != null && !thumbUrl.isEmpty()) return thumbUrl;
            if (midImgUrl != null && !midImgUrl.isEmpty()) return midImgUrl;
            return bigImgUrl != null ? bigImgUrl : "";
        }

        public String getKey() {
            return key;
        }

        public String getAesKey() {
            return key;
        }

        public int getBigLength() {
            return bigLength;
        }

        public int getMidLength() {
            return midLength;
        }

        public int getThumbLength() {
            return thumbLength;
        }
    }
}
