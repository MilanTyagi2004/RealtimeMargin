package com.milan.liquidation_engine.controller;

import com.milan.liquidation_engine.dto.OrderRequest;
import com.milan.liquidation_engine.entity.Position;
import com.milan.liquidation_engine.entity.User;
import com.milan.liquidation_engine.repository.PositionRepository;
import com.milan.liquidation_engine.repository.UserRepository;
import com.milan.liquidation_engine.service.PositionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/positions")
@RequiredArgsConstructor
public class PositionController {

    private final PositionService positionService;
    private final UserRepository userRepository;
    private final PositionRepository positionRepository;

    @PostMapping("/order")
    public ResponseEntity<Position> submitOrder(@RequestBody OrderRequest request) {
        Position position = positionService.processOrder(
                request.getUserId(),
                request.getInstrument(),
                request.getDirection(),
                request.getQuantity(),
                request.getPrice(),
                request.getClientOrderId()
        );
        return ResponseEntity.ok(position);
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<Position>> getUserPositions(@PathVariable Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        List<Position> positions = positionRepository.findByUser(user);
        return ResponseEntity.ok(positions);
    }
}
