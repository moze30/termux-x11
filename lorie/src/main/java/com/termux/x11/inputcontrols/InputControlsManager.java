package com.termux.x11.inputcontrols;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.util.JsonReader;

import androidx.preference.PreferenceManager;

import com.termux.x11.core.AppUtils;
import com.termux.x11.core.FileUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;

public class InputControlsManager {
    private final Context context;
    private ArrayList<ControlsProfile> profiles;
    private int maxProfileId;
    private boolean profilesLoaded = false;

    public InputControlsManager(Context context) {
        this.context = context;
    }

    public static File getProfilesDir(Context context) {
        File profilesDir = new File(context.getFilesDir(), "profiles");
        if (!profilesDir.isDirectory()) profilesDir.mkdir();
        return profilesDir;
    }

    public ArrayList<ControlsProfile> getProfiles() {
        return getProfiles(false);
    }

    public ArrayList<ControlsProfile> getProfiles(boolean ignoreTemplates) {
        if (!profilesLoaded) loadProfiles(ignoreTemplates);
        return profiles;
    }

    /**
     * 内置方案资源版本。凡改动 {@code assets/inputcontrols/profiles} 里的默认方案
     * （新增、改名、调整按键）都需递增，已安装的应用才会在下一次启动时导入。
     *
     * <p>不用应用 versionCode 做门禁：同版本重装时 versionCode 不变，新预设将永远导不进来。
     */
    private static final int ASSET_PROFILES_REVISION = 2;

    private void copyAssetProfilesIfNeeded() {
        File profilesDir = InputControlsManager.getProfilesDir(context);
        if (FileUtils.isEmpty(profilesDir)) {
            // 首次安装：整目录复制
            FileUtils.copy(context, "inputcontrols/profiles", profilesDir);
            return;
        }

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        int oldRevision = preferences.getInt("inputcontrols_assets_revision", 0);
        if (oldRevision >= ASSET_PROFILES_REVISION) return;
        preferences.edit().putInt("inputcontrols_assets_revision", ASSET_PROFILES_REVISION).apply();

        File[] files = profilesDir.listFiles();
        if (files == null) return;

        try {
            AssetManager assetManager = context.getAssets();
            String[] assetFiles = assetManager.list("inputcontrols/profiles");
            if (assetFiles == null) return;

            for (String assetFile : assetFiles) {
                String assetPath = "inputcontrols/profiles/" + assetFile;
                ControlsProfile originProfile = loadProfile(context, assetManager.open(assetPath));
                if (originProfile == null) continue;

                File sameIdFile = null;
                File sameNameFile = null;
                for (File file : files) {
                    ControlsProfile targetProfile = loadProfile(context, file);
                    if (targetProfile == null) continue;
                    if (originProfile.id == targetProfile.id) sameIdFile = file;
                    if (originProfile.getName().equals(targetProfile.getName())) sameNameFile = file;
                }

                if (sameIdFile != null && sameIdFile.equals(sameNameFile)) {
                    // 同 id 同名：属于内置预设，随资源更新覆盖
                    FileUtils.copy(context, assetPath, sameIdFile);
                }
                else if (sameIdFile == null && sameNameFile == null) {
                    // 全新预设：按 controls-{id}.icp 落盘，与 ControlsProfile#getProfileFile 保持一致
                    File dst = ControlsProfile.getProfileFile(context, originProfile.id);
                    if (!dst.exists())
                        FileUtils.copy(context, assetPath, dst);
                }
                // 其余情况（用户改过名或 id 已被占用）保持用户数据不动
            }
        }
        catch (IOException e) {}
    }

    public void loadProfiles(boolean ignoreTemplates) {
        File profilesDir = InputControlsManager.getProfilesDir(context);
        copyAssetProfilesIfNeeded();

        ArrayList<ControlsProfile> profiles = new ArrayList<>();
        File[] files = profilesDir.listFiles();
        if (files != null) {
            for (File file : files) {
                ControlsProfile profile = loadProfile(context, file);
                if (!(ignoreTemplates && profile.isTemplate())) profiles.add(profile);
                maxProfileId = Math.max(maxProfileId, profile.id);
            }
        }

        Collections.sort(profiles);
        this.profiles = profiles;
        profilesLoaded = true;
    }

    public ControlsProfile createProfile(String name) {
        ControlsProfile profile = new ControlsProfile(context, ++maxProfileId);
        profile.setName(name);
        profile.save();
        profiles.add(profile);
        return profile;
    }

    public ControlsProfile duplicateProfile(ControlsProfile source) {
        String newName;
        for (int i = 1;;i++) {
            newName = source.getName() + " ("+i+")";
            boolean found = false;
            for (ControlsProfile profile : profiles) {
                if (profile.getName().equals(newName)) {
                    found = true;
                    break;
                }
            }
            if (!found) break;
        }

        int newId = ++maxProfileId;
        File newFile = ControlsProfile.getProfileFile(context, newId);

        try {
            JSONObject data = new JSONObject(FileUtils.readString(ControlsProfile.getProfileFile(context, source.id)));
            data.put("id", newId);
            data.put("name", newName);
            if (data.has("template")) data.remove("template");
            FileUtils.writeString(newFile, data.toString());
        }
        catch (JSONException e) {}

        ControlsProfile profile = loadProfile(context, newFile);
        profiles.add(profile);
        return profile;
    }

    public void removeProfile(ControlsProfile profile) {
        File file = ControlsProfile.getProfileFile(context, profile.id);
        if (file.isFile() && file.delete()) profiles.remove(profile);
    }

    public ControlsProfile importProfile(JSONObject data) {
        try {
            if (!data.has("id") || !data.has("name")) return null;
            int newId = ++maxProfileId;
            File newFile = ControlsProfile.getProfileFile(context, newId);
            data.put("id", newId);
            FileUtils.writeString(newFile, data.toString());
            ControlsProfile newProfile = loadProfile(context, newFile);

            int foundIndex = -1;
            for (int i = 0; i < profiles.size(); i++) {
                ControlsProfile profile = profiles.get(i);
                if (profile.getName().equals(newProfile.getName())) {
                    foundIndex = i;
                    break;
                }
            }

            if (foundIndex != -1) {
                profiles.set(foundIndex, newProfile);
            }
            else profiles.add(newProfile);
            return newProfile;
        }
        catch (JSONException e) {
            return null;
        }
    }

    public File exportProfile(ControlsProfile profile) {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File destination = new File(downloadsDir, "Winlator-Glibc/profiles/"+profile.getName()+".icp");
        FileUtils.copy(ControlsProfile.getProfileFile(context, profile.id), destination);
        MediaScannerConnection.scanFile(context, new String[]{destination.getAbsolutePath()}, null, null);
        return destination.isFile() ? destination : null;
    }

    public static ControlsProfile loadProfile(Context context, File file) {
        try {
            return loadProfile(context, new FileInputStream(file));
        }
        catch (FileNotFoundException e) {
            return null;
        }
    }

    public static ControlsProfile loadProfile(Context context, InputStream inStream) {
        try (JsonReader reader = new JsonReader(new InputStreamReader(inStream, StandardCharsets.UTF_8))) {
            int profileId = 0;
            String profileName = null;
            float cursorSpeed = Float.NaN;
            int fieldsRead = 0;

            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();

                if (name.equals("id")) {
                    profileId = reader.nextInt();
                    fieldsRead++;
                }
                else if (name.equals("name")) {
                    profileName = reader.nextString();
                    fieldsRead++;
                }
                else if (name.equals("cursorSpeed")) {
                    cursorSpeed = (float) reader.nextDouble();
                    fieldsRead++;
                }
                else {
                    if (fieldsRead == 3) break;
                    reader.skipValue();
                }
            }

            ControlsProfile profile = new ControlsProfile(context, profileId);
            profile.setName(profileName);
            profile.setCursorSpeed(cursorSpeed);
            return profile;
        }
        catch (IOException e) {
            return null;
        }
    }

    public ControlsProfile getProfile(int id) {
        for (ControlsProfile profile : getProfiles()) if (profile.id == id) return profile;
        return null;
    }
}
