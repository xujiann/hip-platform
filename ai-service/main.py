# -*- coding: utf-8 -*-
"""HIP AI 子服务骨架（FastAPI）。
当前为规则版 mock：检验解读 / 影像评分接口先行定义，真实模型到位后替换实现即可，
主平台通过 hip.integration.ai-url 指向本服务，接口契约不变。
启动：uvicorn main:app --port 8100
"""
from fastapi import FastAPI
from pydantic import BaseModel

app = FastAPI(title="HIP AI Service", version="0.1.0")


class LabResult(BaseModel):
    name: str
    value: str
    flag: str | None = None


class LabAnalyzeRequest(BaseModel):
    results: list[LabResult]


RULES = {
    "血红蛋白": {"LL": "重度贫血可能，建议立即复查血常规并评估输血指征",
                 "L": "轻中度贫血，建议补充铁剂并查找失血原因"},
    "白细胞计数": {"HH": "白细胞极高，警惕血液系统疾病，建议血涂片镜检",
                   "H": "白细胞升高，提示感染可能，结合 CRP 判断"},
    "丙氨酸氨基转移酶": {"HH": "转氨酶显著升高，建议肝炎标志物筛查并复查肝功能",
                         "H": "转氨酶轻度升高，建议休息后复查"},
    "空腹血糖": {"H": "血糖升高，建议复测空腹血糖及糖化血红蛋白"},
}


@app.get("/health")
def health():
    return {"status": "ok", "service": "hip-ai", "mode": "rule-mock"}


@app.post("/analyze/lab")
def analyze_lab(req: LabAnalyzeRequest):
    advice, abnormal = [], 0
    for r in req.results:
        flag = (r.flag or "N").upper()
        if flag != "N":
            abnormal += 1
            rule = RULES.get(r.name, {})
            advice.append(rule.get(flag, f"{r.name} 异常（{flag}），建议专科随诊"))
    if not advice:
        advice.append("各项指标未见异常，建议保持定期体检")
    return {"abnormalCount": abnormal, "advice": advice, "model": "rule-mock-v0"}


class ImageAnalyzeRequest(BaseModel):
    modality: str
    description: str | None = None


@app.post("/analyze/image")
def analyze_image(req: ImageAnalyzeRequest):
    return {"modality": req.modality, "riskScore": 0.05,
            "finding": "mock：未见明显异常（真实模型接入后替换）", "model": "rule-mock-v0"}
