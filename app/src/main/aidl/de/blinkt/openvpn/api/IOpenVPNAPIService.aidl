// واجهة التحكم الخارجي بتطبيق "OpenVPN for Android" (de.blinkt.openvpn).
// مأخوذة من مشروع ics-openvpn (GPLv2) كواجهة اتصال فقط. لا يُضمَّن أي كود OpenVPN داخل التطبيق.
// تنبيه: ترتيب الدوال يحدد أرقام الاستدعاء (transaction codes) فيجب ألا يتغير أبداً.
package de.blinkt.openvpn.api;

import de.blinkt.openvpn.api.APIVpnProfile;
import de.blinkt.openvpn.api.IOpenVPNStatusCallback;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.Bundle;

interface IOpenVPNAPIService {
    List<APIVpnProfile> getProfiles();

    void startProfile (String profileUUID);

    boolean addVPNProfile (String name, String config);

    void startVPN (in String inlineconfig);

    Intent prepare (in String packagename);

    Intent prepareVPNService ();

    void disconnect();

    void pause();

    void resume();

    void registerStatusCallback(in IOpenVPNStatusCallback cb);

    void unregisterStatusCallback(in IOpenVPNStatusCallback cb);

    void removeProfile (in String profileUUID);

    boolean protectSocket(in ParcelFileDescriptor fd);

    APIVpnProfile addNewVPNProfile (String name, boolean userEditable, String config);

    void startVPNwithExtras(in String inlineconfig, in Bundle extras);

    APIVpnProfile addNewVPNProfileWithExtras (String name, boolean userEditable, String config, in Bundle extras);

    APIVpnProfile getDefaultProfile();

    void setDefaultProfile (String profileUUID);
}
