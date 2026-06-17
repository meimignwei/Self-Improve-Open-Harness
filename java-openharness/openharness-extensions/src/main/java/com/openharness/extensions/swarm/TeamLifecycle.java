package com.openharness.extensions.swarm;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openharness.common.OpenHarnessObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Persistent team lifecycle management for OpenHarness swarms.
 * Java equivalent of Python swarm/team_lifecycle.py TeamLifecycleManager + all standalone functions.
 */
public class TeamLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(TeamLifecycle.class);
    private static final ObjectMapper MAPPER = OpenHarnessObjectMapper.get();
    private static final Pattern NON_ALPHANUM = Pattern.compile("[^a-zA-Z0-9]");

    // Session cleanup tracking (Python _session_created_teams)
    private static final java.util.Set<String> sessionCreatedTeams = ConcurrentHashMap.newKeySet();

    // ------------------------------------------------------------------
    // Name sanitization
    // ------------------------------------------------------------------

    public static String sanitizeName(String name) {
        return NON_ALPHANUM.matcher(name).replaceAll("-").toLowerCase();
    }

    public static String sanitizeAgentName(String name) {
        return name.replace("@", "-");
    }

    // ------------------------------------------------------------------
    // Team CRUD
    // ------------------------------------------------------------------

    public TeamFile createTeam(String name, String description) {
        Path path = getTeamFilePath(name);
        if (Files.exists(path)) {
            throw new IllegalArgumentException("Team '" + name + "' already exists at " + path);
        }
        TeamFile team = new TeamFile(name, description, System.currentTimeMillis() / 1000.0);
        team.save(path);
        return team;
    }

    public TeamFile createTeam(String name) {
        return createTeam(name, "");
    }

    public void deleteTeam(String name) {
        Path teamDir = getTeamDir(name);
        Path teamFile = teamDir.resolve("team.json");
        if (!Files.exists(teamFile)) {
            throw new IllegalArgumentException("Team '" + name + "' does not exist");
        }
        try {
            deleteDirectory(teamDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete team: " + name, e);
        }
    }

    public TeamFile getTeam(String name) {
        Path path = getTeamFilePath(name);
        if (!Files.exists(path)) return null;
        try {
            return TeamFile.load(path);
        } catch (Exception e) {
            return null;
        }
    }

    public List<TeamFile> listTeams() {
        Path base = Path.of(System.getProperty("user.home"), ".openharness", "teams");
        if (!Files.exists(base)) return List.of();

        List<TeamFile> teams = new ArrayList<>();
        try (var dirs = Files.list(base)) {
            dirs.filter(Files::isDirectory).sorted().forEach(teamDir -> {
                Path teamFile = teamDir.resolve("team.json");
                if (Files.exists(teamFile)) {
                    try {
                        teams.add(TeamFile.load(teamFile));
                    } catch (Exception ignored) {
                    }
                }
            });
        } catch (IOException ignored) {
        }
        return teams;
    }

    // ------------------------------------------------------------------
    // Member management
    // ------------------------------------------------------------------

    public TeamFile addMember(String teamName, TeamMember member) {
        Path path = getTeamFilePath(teamName);
        TeamFile team = requireTeam(teamName, path);
        team.members.put(member.agentId, member);
        team.save(path);
        return team;
    }

    public TeamFile removeMember(String teamName, String agentId) {
        Path path = getTeamFilePath(teamName);
        TeamFile team = requireTeam(teamName, path);
        if (!team.members.containsKey(agentId)) {
            throw new IllegalArgumentException("Agent '" + agentId + "' is not a member of team '" + teamName + "'");
        }
        team.members.remove(agentId);
        team.save(path);
        return team;
    }

    // ------------------------------------------------------------------
    // Mode helpers
    // ------------------------------------------------------------------

    public boolean setMemberMode(String teamName, String memberName, String mode) {
        TeamFile team = getTeam(teamName);
        if (team == null) return false;

        boolean changed = false;
        for (Map.Entry<String, TeamMember> e : team.members.entrySet()) {
            if (e.getValue().name.equals(memberName) && !mode.equals(e.getValue().mode)) {
                TeamMember updated = new TeamMember(e.getValue());
                updated.mode = mode;
                team.members.put(e.getKey(), updated);
                changed = true;
                break;
            }
        }
        if (changed) {
            team.save(getTeamFilePath(teamName));
        }
        return true;
    }

    public void setMultipleMemberModes(String teamName, Map<String, String> nameToMode) {
        TeamFile team = getTeam(teamName);
        if (team == null) return;

        boolean changed = false;
        for (Map.Entry<String, TeamMember> e : team.members.entrySet()) {
            String mode = nameToMode.get(e.getValue().name);
            if (mode != null && !mode.equals(e.getValue().mode)) {
                TeamMember updated = new TeamMember(e.getValue());
                updated.mode = mode;
                team.members.put(e.getKey(), updated);
                changed = true;
            }
        }
        if (changed) {
            team.save(getTeamFilePath(teamName));
        }
    }

    public boolean setMemberActive(String teamName, String memberName, boolean active) {
        TeamFile team = getTeam(teamName);
        if (team == null) return false;

        for (Map.Entry<String, TeamMember> e : team.members.entrySet()) {
            if (e.getValue().name.equals(memberName) && e.getValue().isActive != active) {
                TeamMember updated = new TeamMember(e.getValue());
                updated.isActive = active;
                team.members.put(e.getKey(), updated);
                team.save(getTeamFilePath(teamName));
                return true;
            }
        }
        return false;
    }

    public TeamFile removeMemberByAgentId(String teamName, String agentId) {
        Path path = getTeamFilePath(teamName);
        TeamFile team = requireTeam(teamName, path);
        if (team.members.remove(agentId) != null) {
            team.save(path);
        }
        return team;
    }

    // ------------------------------------------------------------------
    // Hidden pane management
    // ------------------------------------------------------------------

    public void addHiddenPaneId(String teamName, String paneId) {
        Path path = getTeamFilePath(teamName);
        TeamFile team = requireTeam(teamName, path);
        if (!team.hiddenPaneIds.contains(paneId)) {
            team.hiddenPaneIds.add(paneId);
            team.save(path);
        }
    }

    public void removeHiddenPaneId(String teamName, String paneId) {
        Path path = getTeamFilePath(teamName);
        TeamFile team = requireTeam(teamName, path);
        if (team.hiddenPaneIds.remove(paneId)) {
            team.save(path);
        }
    }

    public java.util.Set<String> getHiddenPaneIds(String teamName) {
        TeamFile team = getTeam(teamName);
        if (team == null) return java.util.Set.of();
        return new java.util.LinkedHashSet<>(team.hiddenPaneIds);
    }

    // ------------------------------------------------------------------
    // Orphan pane cleanup
    // ------------------------------------------------------------------

    /**
     * Kill tmux panes that belong to team members no longer in the team file.
     * Keeps any pane whose ID appears in {@code keepPaneIds}.
     */
    public static void killOrphanedTeammatePanes(String teamName, java.util.Set<String> keepPaneIds) {
        TeamLifecycle lifecycle = new TeamLifecycle();
        TeamFile team = lifecycle.getTeam(teamName);
        if (team == null) return;

        for (TeamMember member : team.members.values()) {
            String paneId = member.tmuxPaneId;
            if (paneId != null && !paneId.isEmpty() && !keepPaneIds.contains(paneId)) {
                try {
                    new ProcessBuilder("tmux", "kill-pane", "-t", paneId)
                            .start().waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                    logger.info("Killed orphaned tmux pane {} for member {} (team={})",
                            paneId, member.name, teamName);
                } catch (Exception e) {
                    logger.debug("Failed to kill orphaned pane {}: {}", paneId, e.getMessage());
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Session cleanup
    // ------------------------------------------------------------------

    public static void registerTeamForSessionCleanup(String teamName) {
        sessionCreatedTeams.add(teamName);
    }

    public static void unregisterTeamForSessionCleanup(String teamName) {
        sessionCreatedTeams.remove(teamName);
    }

    public void cleanupSessionTeams() {
        List<String> teams = new ArrayList<>(sessionCreatedTeams);
        for (String teamName : teams) {
            try {
                cleanupTeamDirectories(teamName);
            } catch (Exception e) {
                logger.warn("Failed to clean up team: {}", teamName, e);
            }
        }
        sessionCreatedTeams.clear();
    }

    public void cleanupTeamDirectories(String teamName) {
        TeamFile team = getTeam(teamName);
        if (team != null) {
            for (TeamMember member : team.members.values()) {
                if (member.worktreePath != null) {
                    WorktreeManager.destroyWorktree(Path.of(member.worktreePath));
                }
            }
        }

        Path teamDir = getTeamDir(teamName);
        try {
            deleteDirectory(teamDir);
        } catch (IOException e) {
            logger.warn("Failed to clean up team directory: {}", teamDir, e);
        }
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private TeamFile requireTeam(String name, Path path) {
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Team '" + name + "' does not exist");
        }
        return TeamFile.load(path);
    }

    static Path getTeamFilePath(String teamName) {
        return getTeamDir(teamName).resolve("team.json");
    }

    static Path getTeamDir(String teamName) {
        return Path.of(System.getProperty("user.home"), ".openharness", "teams", teamName);
    }

    private static void deleteDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (var files = Files.walk(dir)) {
                files.sorted(Comparator.reverseOrder())
                        .forEach(f -> {
                            try { Files.deleteIfExists(f); } catch (IOException ignored) {}
                        });
            }
        }
    }

    // ------------------------------------------------------------------
    // TeamFile (Python TeamFile dataclass)
    // ------------------------------------------------------------------

    public static class TeamFile {
        @JsonProperty("name") public String name;
        @JsonProperty("description") public String description = "";
        @JsonProperty("created_at") public double createdAt;
        @JsonProperty("lead_agent_id") public String leadAgentId = "";
        @JsonProperty("lead_session_id") public String leadSessionId;
        @JsonProperty("hidden_pane_ids") public List<String> hiddenPaneIds = new ArrayList<>();
        @JsonProperty("members") public Map<String, TeamMember> members = new LinkedHashMap<>();
        @JsonProperty("team_allowed_paths") public List<AllowedPath> teamAllowedPaths = new ArrayList<>();
        @JsonProperty("allowed_paths") public List<String> allowedPaths = new ArrayList<>();
        @JsonProperty("metadata") public Map<String, Object> metadata = new LinkedHashMap<>();

        public TeamFile() {}

        public TeamFile(String name, String description, double createdAt) {
            this.name = name;
            this.description = description;
            this.createdAt = createdAt;
        }

        public void save(Path path) {
            try {
                Files.createDirectories(path.getParent());
                Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), this);
                Files.move(tmp, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new RuntimeException("Failed to save team file: " + path, e);
            }
        }

        public static TeamFile load(Path path) {
            try {
                return MAPPER.readValue(path.toFile(), TeamFile.class);
            } catch (IOException e) {
                throw new RuntimeException("Failed to load team file: " + path, e);
            }
        }
    }

    // ------------------------------------------------------------------
    // TeamMember (Python TeamMember dataclass)
    // ------------------------------------------------------------------

    public static class TeamMember {
        @JsonProperty("agent_id") public String agentId;
        @JsonProperty("name") public String name;
        @JsonProperty("backend_type") public String backendType;
        @JsonProperty("joined_at") public double joinedAt;
        @JsonProperty("agent_type") public String agentType;
        @JsonProperty("model") public String model;
        @JsonProperty("prompt") public String prompt;
        @JsonProperty("color") public String color;
        @JsonProperty("plan_mode_required") public boolean planModeRequired;
        @JsonProperty("session_id") public String sessionId;
        @JsonProperty("subscriptions") public List<String> subscriptions = new ArrayList<>();
        @JsonProperty("is_active") public boolean isActive = true;
        @JsonProperty("mode") public String mode;
        @JsonProperty("tmux_pane_id") public String tmuxPaneId = "";
        @JsonProperty("cwd") public String cwd = "";
        @JsonProperty("worktree_path") public String worktreePath;
        @JsonProperty("permissions") public List<String> permissions = new ArrayList<>();
        @JsonProperty("status") public String status = "active";

        public TeamMember() {}

        public TeamMember(String agentId, String name, String backendType, double joinedAt) {
            this.agentId = agentId;
            this.name = name;
            this.backendType = backendType;
            this.joinedAt = joinedAt;
        }

        /** Copy constructor. */
        public TeamMember(TeamMember other) {
            this.agentId = other.agentId;
            this.name = other.name;
            this.backendType = other.backendType;
            this.joinedAt = other.joinedAt;
            this.agentType = other.agentType;
            this.model = other.model;
            this.prompt = other.prompt;
            this.color = other.color;
            this.planModeRequired = other.planModeRequired;
            this.sessionId = other.sessionId;
            this.subscriptions = new ArrayList<>(other.subscriptions);
            this.isActive = other.isActive;
            this.mode = other.mode;
            this.tmuxPaneId = other.tmuxPaneId;
            this.cwd = other.cwd;
            this.worktreePath = other.worktreePath;
            this.permissions = new ArrayList<>(other.permissions);
            this.status = other.status;
        }
    }

    // ------------------------------------------------------------------
    // AllowedPath (Python AllowedPath dataclass)
    // ------------------------------------------------------------------

    public static class AllowedPath {
        @JsonProperty("path") public String path;
        @JsonProperty("tool_name") public String toolName;
        @JsonProperty("added_by") public String addedBy;
        @JsonProperty("added_at") public double addedAt;

        public AllowedPath() {}

        public AllowedPath(String path, String toolName, String addedBy, double addedAt) {
            this.path = path;
            this.toolName = toolName;
            this.addedBy = addedBy;
            this.addedAt = addedAt;
        }
    }
}
