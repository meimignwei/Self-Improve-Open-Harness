package com.openharness.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sandbox-runtime integration settings.
 * Java equivalent of Python's SandboxSettings Pydantic model.
 */
public class SandboxSettings {

    private boolean enabled = false;
    private String backend = "srt";
    private boolean failIfUnavailable = false;
    private List<String> enabledPlatforms = new ArrayList<>();
    private SandboxNetworkSettings network = new SandboxNetworkSettings();
    private SandboxFilesystemSettings filesystem = new SandboxFilesystemSettings();
    private DockerSandboxSettings docker = new DockerSandboxSettings();

    public boolean enabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String backend() { return backend; }
    public void setBackend(String backend) { this.backend = backend; }

    public boolean failIfUnavailable() { return failIfUnavailable; }
    public void setFailIfUnavailable(boolean failIfUnavailable) { this.failIfUnavailable = failIfUnavailable; }

    public List<String> enabledPlatforms() { return enabledPlatforms; }
    public void setEnabledPlatforms(List<String> enabledPlatforms) { this.enabledPlatforms = enabledPlatforms; }

    public SandboxNetworkSettings network() { return network; }
    public void setNetwork(SandboxNetworkSettings network) { this.network = network; }

    public SandboxFilesystemSettings filesystem() { return filesystem; }
    public void setFilesystem(SandboxFilesystemSettings filesystem) { this.filesystem = filesystem; }

    public DockerSandboxSettings docker() { return docker; }
    public void setDocker(DockerSandboxSettings docker) { this.docker = docker; }

    public static class SandboxNetworkSettings {
        private List<String> allowedDomains = new ArrayList<>();
        private List<String> deniedDomains = new ArrayList<>();

        public List<String> allowedDomains() { return allowedDomains; }
        public void setAllowedDomains(List<String> allowedDomains) { this.allowedDomains = allowedDomains; }

        public List<String> deniedDomains() { return deniedDomains; }
        public void setDeniedDomains(List<String> deniedDomains) { this.deniedDomains = deniedDomains; }
    }

    public static class SandboxFilesystemSettings {
        private List<String> allowRead = new ArrayList<>();
        private List<String> denyRead = new ArrayList<>();
        private List<String> allowWrite = new ArrayList<>(List.of("."));
        private List<String> denyWrite = new ArrayList<>();

        public List<String> allowRead() { return allowRead; }
        public void setAllowRead(List<String> allowRead) { this.allowRead = allowRead; }

        public List<String> denyRead() { return denyRead; }
        public void setDenyRead(List<String> denyRead) { this.denyRead = denyRead; }

        public List<String> allowWrite() { return allowWrite; }
        public void setAllowWrite(List<String> allowWrite) { this.allowWrite = allowWrite; }

        public List<String> denyWrite() { return denyWrite; }
        public void setDenyWrite(List<String> denyWrite) { this.denyWrite = denyWrite; }
    }

    public static class DockerSandboxSettings {
        private String image = "openharness-sandbox:latest";
        private boolean autoBuildImage = true;
        private double cpuLimit = 0.0;
        private String memoryLimit = "";
        private List<String> extraMounts = new ArrayList<>();
        private Map<String, String> extraEnv = new HashMap<>();

        public String image() { return image; }
        public void setImage(String image) { this.image = image; }

        public boolean autoBuildImage() { return autoBuildImage; }
        public void setAutoBuildImage(boolean autoBuildImage) { this.autoBuildImage = autoBuildImage; }

        public double cpuLimit() { return cpuLimit; }
        public void setCpuLimit(double cpuLimit) { this.cpuLimit = cpuLimit; }

        public String memoryLimit() { return memoryLimit; }
        public void setMemoryLimit(String memoryLimit) { this.memoryLimit = memoryLimit; }

        public List<String> extraMounts() { return extraMounts; }
        public void setExtraMounts(List<String> extraMounts) { this.extraMounts = extraMounts; }

        public Map<String, String> extraEnv() { return extraEnv; }
        public void setExtraEnv(Map<String, String> extraEnv) { this.extraEnv = extraEnv; }
    }
}
