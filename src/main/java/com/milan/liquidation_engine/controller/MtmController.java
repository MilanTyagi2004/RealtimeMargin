package com.milan.liquidation_engine.controller;

import com.milan.liquidation_engine.dto.PriceUpdateRequest;
import com.milan.liquidation_engine.service.MtmService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/v1/mtm")
@RequiredArgsConstructor
public class MtmController {

    private final MtmService mtmService;

    @PostMapping("/price")
    public ResponseEntity<Map<String, Object>> updatePrice(@RequestBody PriceUpdateRequest request) {
        if (request.getInstrument() == null || request.getMarkPrice() == null) {
            throw new IllegalArgumentException("Instrument and mark price must be specified.");
        }
        
        mtmService.updateMarkPrice(request.getInstrument(), request.getMarkPrice());

        Map<String, Object> response = new HashMap<>();
        response.put("status", "SUCCESS");
        response.put("message", "Mark price updated for " + request.getInstrument());
        response.put("price", request.getMarkPrice());

        return ResponseEntity.ok(response);
    }
}
