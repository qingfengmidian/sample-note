# M0 暂无第三方库需要保留规则。
# 历史说明：设计文档 REV3 曾提及 android.icu（CharsetDetector）做编码探测，
# 该方案在 M3 被推翻（真机 hidden API，见提交 4b3693f）；现编码探测为严格解码级联
#（BOM → 严格 UTF-8 → 严格 GBK → Latin-1），解析仅用 JDK/Android 平台 API（XmlPullParser），无额外 keep 负担。
