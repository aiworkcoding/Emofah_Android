import { useState } from 'react';

interface BrandMarkProps {
  size?: 'sm' | 'md' | 'lg' | 'xl';
  showWordmark?: boolean;
  showTagline?: boolean;
}

const SIZE_MAP = {
  sm: 'h-7 w-7',
  md: 'h-9 w-9',
  lg: 'h-12 w-12',
  xl: 'h-20 w-20',
};

export function BrandMark({
  size = 'md',
  showWordmark = false,
  showTagline = false,
}: BrandMarkProps) {
  const [imgError, setImgError] = useState(false);

  return (
    <div className="flex items-center gap-3 min-w-0">
      <div
        className={`relative shrink-0 flex items-center justify-center rounded-xl bg-white/80 dark:bg-[#0d1f36]/90 p-1.5 ring-1 ring-[#103c60]/10 dark:ring-[#43b1d4]/20 shadow-xs ${SIZE_MAP[size]}`}
      >
        {!imgError ? (
          <img
            src="/assets/aistudio/EMOFAH_LOGO_386a0951.svg"
            alt="Emofah Agencies Emblem"
            referrerPolicy="no-referrer"
            onError={() => setImgError(true)}
            className="h-full w-full object-contain"
          />
        ) : (
          <svg
            viewBox="0 0 32 32"
            fill="none"
            className="h-full w-full"
            aria-hidden="true"
          >
            <polygon points="16,2 21,12 11,12" fill="#14558a" />
            <polygon points="29,12 19,17 21,11" fill="#43b1d4" />
            <polygon points="24,28 16,20 22,16" fill="#348fc2" />
            <polygon points="8,28 10,17 16,20" fill="#43b1d4" />
            <polygon points="3,12 13,11 11,17" fill="#103c60" />
          </svg>
        )}
      </div>

      {showWordmark && (
        <div className="min-w-0">
          <div className="font-display font-bold tracking-tight text-[#103c60] dark:text-[#f1f5f9] leading-none truncate">
            EMOFAH{' '}
            <span className="font-semibold text-[#1768a7] dark:text-[#43b1d4]">
              AGENCIES
            </span>
          </div>
          {showTagline && (
            <p className="mt-1 text-xs text-[#475569] dark:text-[#94a3b8] truncate">
              Equipping Work. Enabling Progress
            </p>
          )}
        </div>
      )}
    </div>
  );
}
