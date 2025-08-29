package com.example.pocProvisioning.provisioning;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/provisioning")
public class ProvisioningController {
    @Autowired
    private ProvisioningService provisioningService;

    @PostMapping
    public ProvisioningRequest submitProvisioningRequest(@RequestBody ProvisioningRequest request) {
        return provisioningService.saveRequest(request);
    }
}
