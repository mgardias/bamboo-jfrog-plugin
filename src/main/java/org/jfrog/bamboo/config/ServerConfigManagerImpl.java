package org.jfrog.bamboo.config;

import com.atlassian.bamboo.bandana.PlanAwareBandanaContext;
import com.atlassian.bandana.BandanaManager;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jfrog.bamboo.utils.Utils;
import org.springframework.stereotype.Component;

import javax.inject.Inject;
import java.io.IOException;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages the server configurations for JFrog.
 */
@Component
public class ServerConfigManagerImpl implements ServerConfigManager {

    private final transient Logger log = LogManager.getLogger(ServerConfigManagerImpl.class);

    private static final String JFROG_CONFIG_KEY = "org.jfrog.bamboo.configurations.v1";
    private final List<ServerConfig> configuredServers = new CopyOnWriteArrayList<>();
    @ComponentImport
    private BandanaManager bandanaManager = null;
    private final ObjectMapper mapper = Utils.createMapper();

    @Inject
    public ServerConfigManagerImpl(BandanaManager bandanaManager) {
        this.setBandanaManager(bandanaManager);
    }

    /**
     * Get all server configurations.
     *
     * @return List of server configurations.
     */
    @Override
    public List<ServerConfig> getAllServerConfigs() {
        return new ArrayList<>(configuredServers);
    }

    /**
     * Get server configuration by ID.
     *
     * @param serverId The ID of the server configuration to retrieve.
     * @return The server configuration matching the given ID, or null if not found.
     */
    @Override
    public ServerConfig getServerConfigById(String serverId) {
        for (ServerConfig configuredServer : configuredServers) {
            if (Objects.equals(configuredServer.getServerId(), serverId)) {
                return configuredServer;
            }
        }
        return null;
    }

    /**
     * Add a server configuration.
     *
     * @param serverConfig The server configuration to add.
     */
    @Override
    public void addServerConfiguration(ServerConfig serverConfig) {
        configuredServers.add(serverConfig);
        try {
            persist();
        } catch (IllegalAccessException | UnsupportedEncodingException | JsonProcessingException e) {
            log.error("Could not add JFrog configuration.", e);
        }
    }

    /**
     * Delete a server configuration by ID.
     *
     * @param serverId The ID of the server configuration to delete.
     */
    @Override
    public void deleteServerConfiguration(final String serverId) {
        for (ServerConfig configuredServer : configuredServers) {
            if (Objects.equals(configuredServer.getServerId(), serverId)) {
                configuredServers.remove(configuredServer);
                try {
                    persist();
                } catch (IllegalAccessException | UnsupportedEncodingException | JsonProcessingException e) {
                    log.error("Could not delete JFrog configuration.", e);
                }
                break;
            }
        }
    }

    /**
     * Update a server configuration.
     *
     * @param updated The updated server configuration.
     */
    @Override
    public void updateServerConfiguration(ServerConfig updated) {
        for (ServerConfig configuredServer : configuredServers) {
            if (Objects.equals(configuredServer.getServerId(), updated.getServerId())) {
                configuredServer.copy(updated);
                try {
                    persist();
                } catch (IllegalAccessException | UnsupportedEncodingException | JsonProcessingException e) {
                    log.error("Could not update JFrog configuration.", e);
                }
                break;
            }
        }
    }

    private void setBandanaManager(BandanaManager bandanaManager) {
        this.bandanaManager = bandanaManager;
        try {
            String existingServersListAction = (String) bandanaManager.getValue(PlanAwareBandanaContext.GLOBAL_CONTEXT, JFROG_CONFIG_KEY);
            if (StringUtils.isNotBlank(existingServersListAction)) {
                List<ServerConfig> serverConfigList = mapper.readValue(existingServersListAction, new TypeReference<>() {
                });
                for (ServerConfig serverConfig : serverConfigList) {
                    // Because of some class loader issues we had to get a workaround,
                    // we serialize and deserialize the serverConfig object.
                    ObjectWriter ow = new ObjectMapper().writer().withDefaultPrettyPrinter();
                    String json = ow.writeValueAsString(serverConfig);
                    ServerConfig tempServerConfig = new ObjectMapper().readValue(json, ServerConfig.class);
                    ServerConfig decryptedConfig = new ServerConfig();
                    decryptedConfig.copy(serverConfig);
                    decryptedConfig.setPassword(EncryptionHelper.decrypt(tempServerConfig.getPassword()));
                    decryptedConfig.setAccessToken(EncryptionHelper.decrypt(tempServerConfig.getAccessToken()));
                    configuredServers.add(decryptedConfig);
                }
            }
        } catch (IOException e) {
            log.error("Could not load JFrog configuration.", e);
        }
    }

    @Override
    public synchronized void persist() throws IllegalAccessException, UnsupportedEncodingException, JsonProcessingException {
        List<ServerConfig> serverConfigs = new ArrayList<>();
        for (ServerConfig serverConfig : configuredServers) {
            ServerConfig encryptedConfig = new ServerConfig();
            encryptedConfig.copy(serverConfig);
            encryptedConfig.setPassword(EncryptionHelper.encryptForConfig(serverConfig.getPassword()));
            encryptedConfig.setAccessToken(EncryptionHelper.encryptForConfig(serverConfig.getAccessToken()));
            serverConfigs.add(encryptedConfig);
        }
        String serverConfigsString = mapper.writeValueAsString(serverConfigs);
        bandanaManager.setValue(PlanAwareBandanaContext.GLOBAL_CONTEXT, JFROG_CONFIG_KEY, serverConfigsString);
    }
}
