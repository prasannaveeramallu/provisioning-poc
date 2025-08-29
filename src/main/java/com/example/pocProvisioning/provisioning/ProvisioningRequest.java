package com.example.pocProvisioning.provisioning;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.util.List;
import java.util.Map;

@Document(collection = "provisioning_requests")
public class ProvisioningRequest {
    @Id
    private String id;
    private List<ResourceRequest> resources;
    private String status; // PENDING, IN_PROGRESS, DONE, SUCCESSFUL, COMPLETED, FAILED
    private List<String> errorMessages; // error details for failed resources
    private String createdAt;
    private String updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public List<ResourceRequest> getResources() { return resources; }
    public void setResources(List<ResourceRequest> resources) { this.resources = resources; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
    public List<String> getErrorMessages() { return errorMessages; }
    public void setErrorMessages(List<String> errorMessages) { this.errorMessages = errorMessages; }

    public static class ResourceRequest {
    private String type; // s3, ec2, azure-keyvault, azure-vm
        private Map<String, Object> config;
        // Getters and setters
        // ...
            public String getType() { return type; }
            public void setType(String type) { this.type = type; }
            public Map<String, Object> getConfig() { return config; }
            public void setConfig(Map<String, Object> config) { this.config = config; }
    }
}
