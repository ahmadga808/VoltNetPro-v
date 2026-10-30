package de.blinkt.openvpn.api;

import android.os.Parcel;
import android.os.Parcelable;

/** نسخة مبسّطة من APIVpnProfile في ics-openvpn. لا يستعملها التطبيق فعلياً (نستخدم startVPN فقط)، وُجدت ليُترجم ملف AIDL. */
public class APIVpnProfile implements Parcelable {
    public final String mUUID;
    public final String mName;
    public final boolean mUserEditable;
    public final String mProfileCreator;

    public APIVpnProfile(Parcel in) {
        mUUID = in.readString();
        mName = in.readString();
        mUserEditable = in.readInt() != 0;
        mProfileCreator = in.readString();
    }

    public APIVpnProfile(String uuidString, String name, boolean userEditable, String profileCreator) {
        mUUID = uuidString;
        mName = name;
        mUserEditable = userEditable;
        mProfileCreator = profileCreator;
    }

    @Override
    public int describeContents() { return 0; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mUUID);
        dest.writeString(mName);
        dest.writeInt(mUserEditable ? 1 : 0);
        dest.writeString(mProfileCreator);
    }

    public static final Parcelable.Creator<APIVpnProfile> CREATOR = new Parcelable.Creator<APIVpnProfile>() {
        public APIVpnProfile createFromParcel(Parcel in) { return new APIVpnProfile(in); }
        public APIVpnProfile[] newArray(int size) { return new APIVpnProfile[size]; }
    };
}
