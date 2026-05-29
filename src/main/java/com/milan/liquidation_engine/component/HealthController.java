package com.milan.liquidation_engine.component;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;



@RestController
@RequestMapping("v1")
public class HealthController {
    @GetMapping("/health")
    public String getUser(){
        return "okay";
    }
}
