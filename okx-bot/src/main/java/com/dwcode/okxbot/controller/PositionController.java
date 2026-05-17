package com.dwcode.okxbot.controller;

import com.dwcode.okxbot.common.response.ApiResult;
import com.dwcode.okxbot.trading.position.entity.PositionEntity;
import com.dwcode.okxbot.trading.position.service.PositionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 持仓接口。
 */
@RestController
@RequestMapping("/api/positions")
@RequiredArgsConstructor
public class PositionController {

    private final PositionService positionService;

    @GetMapping
    public ApiResult<List<PositionEntity>> listPositions() {
        return ApiResult.ok(positionService.listPositions());
    }
}
