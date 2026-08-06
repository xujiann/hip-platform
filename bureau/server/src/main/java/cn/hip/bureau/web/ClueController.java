package cn.hip.bureau.web;

import cn.hip.bureau.entity.CaseClue;
import cn.hip.bureau.repository.CaseClueRepository;
import cn.hip.bureau.service.ClueService;
import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/bureau/clues")
@RequiredArgsConstructor
public class ClueController {

    private final ClueService clueService;
    private final CaseClueRepository clueRepository;

    @GetMapping
    public R<List<CaseClue>> list(@RequestParam(required = false) String status) {
        return R.ok(status == null ? clueRepository.findTop200ByOrderByIdDesc()
                : clueRepository.findByStatusOrderByIdDesc(status));
    }

    @PostMapping
    public R<CaseClue> create(@RequestBody ClueService.ClueCreateReq req, Authentication auth) {
        return R.ok(clueService.create(req, auth.getName()));
    }

    @PostMapping("/{id}/extend")
    public R<CaseClue> extend(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return R.ok(clueService.extend(id, body.get("reason")));
    }

    @PostMapping("/{id}/reject")
    public R<CaseClue> reject(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return R.ok(clueService.reject(id, body.get("verifyResult")));
    }
}
