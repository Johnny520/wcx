package com.tencent.mm.protocal.protobuf;

import java.util.LinkedList;

public class SnsObject {

    public LinkedList<Object> LikeUserList = new LinkedList<>();
    public int LikeUserListCount;
    public int LikeCount;
    public int LikeFlag;

    public byte[] toByteArray() {
        throw new RuntimeException("Stub!");
    }
}
