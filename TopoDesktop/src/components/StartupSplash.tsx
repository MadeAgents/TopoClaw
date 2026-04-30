import appIcon from '../../Image_PC_rounded.png'

import { useState } from 'react'

interface StartupSplashProps {
  subtitle?: string
  details?: string[]
}

export function StartupSplash({ subtitle = '正在加载，请稍候...', details = [] }: StartupSplashProps) {
  const [expanded, setExpanded] = useState(false)
  const hasDetails = details.length > 0

  return (
    <div className="startup-splash" role="status" aria-live="polite" aria-label="应用加载中">
      <div className="startup-splash-spinner-wrap">
        <div className="startup-splash-spinner" />
        <img src={appIcon} alt="TopoClaw" className="startup-splash-icon" />
      </div>
      <div className="startup-splash-title">TopoClaw</div>
      <div className="startup-splash-subtitle">{subtitle}</div>
      {hasDetails && (
        <>
          <button
            type="button"
            className="startup-splash-toggle-details"
            onClick={() => setExpanded((v) => !v)}
            aria-expanded={expanded}
          >
            {expanded ? '收起详情' : '展开详情'}
          </button>
          {expanded && (
            <div className="startup-splash-details" role="log" aria-live="polite">
              {details.map((line, index) => (
                <div key={`${index}-${line}`} className="startup-splash-details-line">
                  {line}
                </div>
              ))}
            </div>
          )}
        </>
      )}
    </div>
  )
}
