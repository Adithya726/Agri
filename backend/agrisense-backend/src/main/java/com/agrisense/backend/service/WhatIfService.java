package com.agrisense.backend.service;

import com.agrisense.backend.dto.WhatIfRequest;
import com.agrisense.backend.dto.WhatIfResponse;

public interface WhatIfService {

    WhatIfResponse analyze(Long farmId, WhatIfRequest request);
}
