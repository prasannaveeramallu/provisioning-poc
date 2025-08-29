package com.example.pocProvisioning.provisioning;


import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.compute.models.KnownLinuxVirtualMachineImage;
import com.azure.resourcemanager.compute.models.VirtualMachineSizeTypes;
import com.azure.core.management.profile.AzureProfile;
import com.azure.core.management.AzureEnvironment;
import com.example.pocProvisioning.provisioning.ProvisioningRequest.ResourceRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
// AWS SDK imports
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.SetBucketVersioningConfigurationRequest;
import com.amazonaws.services.s3.model.BucketVersioningConfiguration;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
// Azure SDK imports
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
// Azure ResourceManager SDK imports

import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
@Service
public class ProvisioningService {
    @Autowired
    private NotificationService notificationService;
    @Value("${scheduler_task_interval:10}")
    private int schedulerIntervalSeconds;
    @org.springframework.beans.factory.annotation.Value("${AWS_ACCESS_KEY_ID}")
    private String awsAccessKeyId;
    @org.springframework.beans.factory.annotation.Value("${AWS_SECRET_ACCESS_KEY}")
    private String awsSecretAccessKey;
    @org.springframework.beans.factory.annotation.Value("${AWS_REGION}")
    private String awsRegion;
    @org.springframework.beans.factory.annotation.Value("${AZURE_CLIENT_ID:}")
    private String azureClientId;
    @org.springframework.beans.factory.annotation.Value("${AZURE_TENANT_ID:}")
    private String azureTenantId;
    @org.springframework.beans.factory.annotation.Value("${AZURE_CLIENT_SECRET:}")
    private String azureClientSecret;
    @Autowired
    private MongoTemplate mongoTemplate;

    private void provisionAzureVM(Object configObj) {
        if (!(configObj instanceof java.util.Map)) return;
    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> config = (java.util.Map<String, Object>) configObj;
        String baseName = (String) config.get("vmName");
        String uniqueSuffix = String.valueOf(System.currentTimeMillis());
        String vmName = baseName + "-" + uniqueSuffix;
        notificationService.sendNotification("Azure VM provisioning started: " + vmName);
        try {
            // Authenticate with Azure using credentials
            ClientSecretCredential credential = new ClientSecretCredentialBuilder()
                .clientId(azureClientId)
                .clientSecret(azureClientSecret)
                .tenantId(azureTenantId)
                .build();
            String subscriptionId = (String) config.getOrDefault("subscriptionId", System.getenv("AZURE_SUBSCRIPTION_ID"));
            AzureProfile profile = new AzureProfile(azureTenantId, subscriptionId, AzureEnvironment.AZURE);
            AzureResourceManager azure = AzureResourceManager
                .authenticate(credential, profile)
                .withSubscription(subscriptionId);
            // Minimal VM creation (requires resource group, region, image, size)
            String resourceGroup = (String) config.getOrDefault("resourceGroup", "myResourceGroup-1756469986758");
            String region = (String) config.getOrDefault("region", "eastus");
            String adminUser = (String) config.getOrDefault("adminUsername", "azureuser");
            String adminPass = (String) config.getOrDefault("adminPassword", "Password123!");
            String vmSize = (String) config.getOrDefault("vmSize", "Standard_B1s");
            
            // Use existing resource group 
            azure.virtualMachines().define(vmName)
                .withRegion(region)
                .withExistingResourceGroup(resourceGroup)
                .withNewPrimaryNetwork("10.0.0.0/28")
                .withPrimaryPrivateIPAddressDynamic()
                .withoutPrimaryPublicIPAddress()  
                .withPopularLinuxImage(com.azure.resourcemanager.compute.models.KnownLinuxVirtualMachineImage.UBUNTU_SERVER_18_04_LTS)
                .withRootUsername(adminUser)
                .withRootPassword(adminPass)
                .withSize(com.azure.resourcemanager.compute.models.VirtualMachineSizeTypes.fromString(vmSize))
                .create();
            System.out.println("Provisioned Azure VM: " + vmName);
            notificationService.sendNotification("Azure VM provisioning completed: " + vmName);
        } catch (Exception e) {
            notificationService.sendNotification("Azure VM provisioning failed: " + vmName + " Reason: " + e.getMessage());
            throw e; // Rethrow to allow main error handler to capture it
        }
    }

    public ProvisioningRequest saveRequest(ProvisioningRequest request) {
        request.setStatus("PENDING");
        // set createdAt, updatedAt
        return mongoTemplate.save(request);
    }

    public void processRequest(ProvisioningRequest request) {
        request.setStatus("IN_PROGRESS");
        mongoTemplate.save(request);
        List<String> errors = new java.util.ArrayList<>();
        for (ResourceRequest resource : request.getResources()) {
            try {
                switch (resource.getType()) {
                    case "s3":
                        provisionS3(resource.getConfig());
                        break;
                    case "ec2":
                        provisionEC2(resource.getConfig());
                        break;
                    case "azure-keyvault":
                        provisionAzureKeyVault(resource.getConfig());
                        break;
                    case "azure-vm":
                        provisionAzureVM(resource.getConfig());
                        break;
                }
            } catch (Exception e) {
                errors.add(resource.getType() + ": " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            request.setErrorMessages(errors);
            if (errors.size() == request.getResources().size()) {
                request.setStatus("FAILED");
            } else {
                request.setStatus("PARTIALLY_FAILED");
            }
        } else {
            request.setStatus("DONE");
        }
        mongoTemplate.save(request);
    }

    private void provisionS3(Object configObj) {
    if (!(configObj instanceof java.util.Map)) return;
    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> config = (java.util.Map<String, Object>) configObj;
    String baseName = (String) config.get("bucketName");
    notificationService.sendNotification("S3 bucket provisioning started: " + baseName);
    String uniqueSuffix = String.valueOf(System.currentTimeMillis());
    String bucketName = baseName + "-" + uniqueSuffix;
    Boolean enableVersioning = (Boolean) config.getOrDefault("enableVersioning", false);
        com.amazonaws.services.s3.AmazonS3 s3 = s3Client();
        if (!s3.doesBucketExistV2(bucketName)) {
            s3.createBucket(bucketName);
            notificationService.sendNotification("S3 bucket created: " + bucketName);
        }
        if (enableVersioning) {
            com.amazonaws.services.s3.model.SetBucketVersioningConfigurationRequest versioningRequest =
                new com.amazonaws.services.s3.model.SetBucketVersioningConfigurationRequest(
                    bucketName,
                    new com.amazonaws.services.s3.model.BucketVersioningConfiguration(
                        com.amazonaws.services.s3.model.BucketVersioningConfiguration.ENABLED));
            s3.setBucketVersioningConfiguration(versioningRequest);
            notificationService.sendNotification("S3 bucket versioning enabled: " + bucketName);
        }
        System.out.println("Provisioned S3 bucket: " + bucketName + ", versioning: " + enableVersioning);
        notificationService.sendNotification("S3 bucket provisioning completed: " + bucketName);
    }

    private void provisionEC2(Object configObj) {
        if (!(configObj instanceof java.util.Map)) return;
    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> config = (java.util.Map<String, Object>) configObj;
        String instanceType = (String) config.get("instanceType");
        String amiId = (String) config.get("amiId");
        String keyName = (String) config.get("keyName");
        notificationService.sendNotification("EC2 instance provisioning started: " + instanceType);
        com.amazonaws.services.ec2.AmazonEC2 ec2 = ec2Client();
        com.amazonaws.services.ec2.model.RunInstancesRequest req = new com.amazonaws.services.ec2.model.RunInstancesRequest()
            .withImageId(amiId)
            .withInstanceType(instanceType)
            .withMinCount(1)
            .withMaxCount(1)
            .withKeyName(keyName);

        // Add tag specifications if provided
        Object tagSpecsObj = config.get("tagSpecifications");
        if (tagSpecsObj instanceof java.util.List) {
            java.util.List<?> tagSpecsList = (java.util.List<?>) tagSpecsObj;
            java.util.List<com.amazonaws.services.ec2.model.TagSpecification> tagSpecifications = new java.util.ArrayList<>();
            for (Object specObj : tagSpecsList) {
                if (specObj instanceof java.util.Map) {
                    java.util.Map<?,?> specMap = (java.util.Map<?,?>) specObj;
                    String resourceType = (String) specMap.get("ResourceType");
                    Object tagsObj = specMap.get("Tags");
                    java.util.List<com.amazonaws.services.ec2.model.Tag> tags = new java.util.ArrayList<>();
                    if (tagsObj instanceof java.util.List) {
                        for (Object tagObj : (java.util.List<?>) tagsObj) {
                            if (tagObj instanceof java.util.Map) {
                                java.util.Map<?,?> tagMap = (java.util.Map<?,?>) tagObj;
                                String key = (String) tagMap.get("Key");
                                String value = (String) tagMap.get("Value");
                                tags.add(new com.amazonaws.services.ec2.model.Tag(key, value));
                            }
                        }
                    }
                    com.amazonaws.services.ec2.model.TagSpecification tagSpec = new com.amazonaws.services.ec2.model.TagSpecification()
                        .withResourceType(resourceType)
                        .withTags(tags);
                    tagSpecifications.add(tagSpec);
                }
            }
            req.withTagSpecifications(tagSpecifications);
        }

        com.amazonaws.services.ec2.model.RunInstancesResult result = ec2.runInstances(req);
        System.out.println("Provisioned EC2 instance: type=" + instanceType + ", ami=" + amiId + ", key=" + keyName);
        notificationService.sendNotification("EC2 instance provisioning completed: " + instanceType);
    }

    private void provisionAzureKeyVault(Object configObj) {
        if (!(configObj instanceof java.util.Map)) return;
    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> config = (java.util.Map<String, Object>) configObj;
    String baseName = (String) config.get("vaultName");
    String uniqueSuffix = String.valueOf(System.currentTimeMillis());
    String vaultName = baseName + "-" + uniqueSuffix;
    notificationService.sendNotification("Azure Key Vault provisioning started: " + vaultName);
    com.azure.identity.DefaultAzureCredentialBuilder credBuilder = new com.azure.identity.DefaultAzureCredentialBuilder();
    com.azure.core.credential.TokenCredential credential = credBuilder.build();
    com.azure.security.keyvault.secrets.SecretClient client = new com.azure.security.keyvault.secrets.SecretClientBuilder()
        .vaultUrl("https://" + vaultName + ".vault.azure.net/")
        .credential(credential)
        .buildClient();
    // Example: create a dummy secret to verify vault access
    client.setSecret("provisioning-test", "success");
    System.out.println("Provisioned Azure Key Vault: " + vaultName);
    notificationService.sendNotification("Azure Key Vault provisioning completed: " + vaultName);
}

    @Scheduled(fixedDelayString = "#{${scheduler_task_interval:10} * 1000}")
    public void scheduledProvisioning() {
        // Check if any request is in progress
        List<ProvisioningRequest> inProgress = mongoTemplate.find(
            org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("status").is("IN_PROGRESS")
            ), ProvisioningRequest.class);

        if (!inProgress.isEmpty()) {
            // Wait until current request is done/completed
            return;
        }

        // Pick the oldest pending request
        List<ProvisioningRequest> pending = mongoTemplate.find(
            org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("status").is("PENDING")
            ).limit(1), ProvisioningRequest.class);

        if (!pending.isEmpty()) {
            processRequest(pending.get(0));
        }
        // If no pending, do nothing
    }

    // AWS S3 client builder
    private AmazonS3 s3Client() {
        return AmazonS3ClientBuilder.standard()
            .withRegion(awsRegion)
            .withCredentials(new AWSStaticCredentialsProvider(
                new BasicAWSCredentials(awsAccessKeyId, awsSecretAccessKey)))
            .build();
    }

    // AWS EC2 client builder
    private AmazonEC2 ec2Client() {
        return AmazonEC2ClientBuilder.standard()
            .withRegion(awsRegion)
            .withCredentials(new AWSStaticCredentialsProvider(
                new BasicAWSCredentials(awsAccessKeyId, awsSecretAccessKey)))
            .build();
    }
}
