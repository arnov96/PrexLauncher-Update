package com.prex.launcher.core;

import java.util.List;

/** Mirror of https://piston-meta.mojang.com/mc/game/version_manifest_v2.json */
public class VersionManifest {

    public Latest latest;
    public List<Version> versions;

    public static class Latest {
        public String release;
        public String snapshot;
    }

    public static class Version {
        public String id;
        public String type;        // release | snapshot | old_beta | old_alpha
        public String url;
        public String time;
        public String releaseTime;
    }
}
