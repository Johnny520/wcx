package com.tencent.mm.protocal.protobuf;

import java.util.LinkedList;

public class SnsObject {

    public String Username;
    public LinkedList<Object> LikeUserList = new LinkedList<>();
    public int LikeUserListCount;
    public int LikeCount;
    public int LikeFlag;
    public LinkedList<Object> CommentUserList = new LinkedList<>();
    public int CommentUserListCount;
    public int CommentCount;

    public byte[] toByteArray() {
        throw new RuntimeException("Stub!");
    }
}
