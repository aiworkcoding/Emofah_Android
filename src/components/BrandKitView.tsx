import { useState } from 'react';
import { Check, Copy } from 'lucide-react';
import { motion } from 'motion/react';

interface BrandKitViewProps {
  reducedMotion: boolean;
}

interface BrandSwatch {
  name: string;
  role: string;
  hex: string;
  textOnSwatch: string;
}

const BRAND_PALETTE: BrandSwatch[] = [
  {
    name: 'Deep Navy',
    role: 'Primary Ink & Light Headings',
    hex: '#103c60',
    textOnSwatch: '#f8fafc',
  },
  {
    name: 'Royal Ocean',
    role: 'Primary Light-Mode CTA',
    hex: '#14558a',
    textOnSwatch: '#f8fafc',
  },
  {
    name: 'Mid Azure',
    role: 'Interactive Hover & Focus',
    hex: '#1768a7',
    textOnSwatch: '#f8fafc',
  },
  {
    name: 'Steel Blue',
    role: 'Secondary Surface Accent',
    hex: '#348fc2',
    textOnSwatch: '#060d18',
  },
  {
    name: 'Sky Cyan',
    role: 'Primary Dark-Mode Accent',
    hex: '#43b1d4',
    textOnSwatch: '#060d18',
  },
];

export function BrandKitView({ reducedMotion }: BrandKitViewProps) {
  const [copiedHex, setCopiedHex] = useState<string | null>(null);
  const [logoError, setLogoError] = useState({ emblem: false, full: false });

  const copyColor = async (hex: string) => {
    try {
      await navigator.clipboard.writeText(hex);
      setCopiedHex(hex);
      window.setTimeout(() => {
        setCopiedHex((prev) => (prev === hex ? null : prev));
      }, 1500);
    } catch {
      setCopiedHex(hex);
    }
  };

  return (
    <div className="space-y-8">
      <div>
        <h1 className="font-display text-2xl sm:text-3xl font-bold tracking-tight text-[#103c60] dark:text-[#f1f5f9]">
          Brand & Glass System
        </h1>
        <p className="mt-1 text-sm text-[#475569] dark:text-[#94a3b8]">
          Consistent Emofah Agencies visual assets and palette across light and
          dark surfaces.
        </p>
      </div>

      <section aria-label="Logo Assets" className="grid grid-cols-1 md:grid-cols-2 gap-5">
        <div className="emofah-glass rounded-3xl p-6 flex flex-col justify-between gap-5">
          <div className="flex items-center justify-between">
            <h2 className="font-display text-base font-semibold text-[#103c60] dark:text-[#f1f5f9]">
              Primary Star Emblem
            </h2>
            <span className="text-xs text-[#64748b] dark:text-[#94a3b8]">
              Adaptive Surface
            </span>
          </div>

          <div className="h-44 rounded-2xl bg-[#f8fafc] dark:bg-[#091526] border border-[#103c60]/10 dark:border-[#43b1d4]/15 flex items-center justify-center p-6">
            {!logoError.emblem ? (
              <img
                src="/assets/aistudio/EMOFAH_LOGO_386a0951.svg"
                alt="Emofah Star Emblem"
                referrerPolicy="no-referrer"
                onError={() => setLogoError((p) => ({ ...p, emblem: true }))}
                className="h-32 w-32 object-contain"
              />
            ) : (
              <span className="font-display font-bold text-[#103c60] dark:text-[#43b1d4]">
                EMOFAH
              </span>
            )}
          </div>

          <p className="text-xs leading-relaxed text-[#475569] dark:text-[#94a3b8]">
            App launcher icon, navigation mark, and compact header emblem.
          </p>
        </div>

        <div className="emofah-glass rounded-3xl p-6 flex flex-col justify-between gap-5">
          <div className="flex items-center justify-between">
            <h2 className="font-display text-base font-semibold text-[#103c60] dark:text-[#f1f5f9]">
              Full Agency Lockup
            </h2>
            <span className="text-xs text-[#64748b] dark:text-[#94a3b8]">
              Light Canvas Plate
            </span>
          </div>

          <div className="h-44 rounded-2xl bg-[#f8f8f8] border border-[#103c60]/10 flex items-center justify-center p-4">
            {!logoError.full ? (
              <img
                src="/assets/aistudio/EMOFAH_AGENCIES_50f1b2e4.svg"
                alt="Emofah Agencies Full Lockup"
                referrerPolicy="no-referrer"
                onError={() => setLogoError((p) => ({ ...p, full: true }))}
                className="h-36 w-auto object-contain"
              />
            ) : (
              <span className="font-display font-bold text-[#103c60]">
                EMOFAH AGENCIES
              </span>
            )}
          </div>

          <p className="text-xs leading-relaxed text-[#475569] dark:text-[#94a3b8]">
            Mounted on a clean #f8f8f8 plate in dark mode so deep navy
            lettering preserves full legibility.
          </p>
        </div>
      </section>

      <section aria-label="Color Palette" className="space-y-4">
        <div className="flex items-baseline justify-between">
          <h2 className="font-display text-lg font-semibold text-[#103c60] dark:text-[#f1f5f9]">
            Extracted Brand Palette
          </h2>
          <span className="text-xs text-[#64748b] dark:text-[#94a3b8]">
            Tap any swatch to copy hex
          </span>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-5 gap-3.5">
          {BRAND_PALETTE.map((swatch) => {
            const isCopied = copiedHex === swatch.hex;
            return (
              <motion.button
                key={swatch.hex}
                type="button"
                whileTap={reducedMotion ? undefined : { scale: 0.97 }}
                onClick={() => copyColor(swatch.hex)}
                className="emofah-glass rounded-2xl p-3 text-left flex flex-col gap-3 cursor-pointer hover:border-[#1768a7]/35 dark:hover:border-[#43b1d4]/40 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#43b1d4]"
              >
                <div
                  className="h-20 w-full rounded-xl flex items-end justify-between p-3"
                  style={{ backgroundColor: swatch.hex, color: swatch.textOnSwatch }}
                >
                  <span className="font-mono-data text-xs font-medium uppercase">
                    {swatch.hex}
                  </span>
                  {isCopied ? (
                    <Check className="w-4 h-4 shrink-0" />
                  ) : (
                    <Copy className="w-3.5 h-3.5 opacity-80 shrink-0" />
                  )}
                </div>

                <div className="px-1 pb-1">
                  <div className="font-display text-sm font-semibold text-[#103c60] dark:text-[#f1f5f9]">
                    {isCopied ? 'Copied' : swatch.name}
                  </div>
                  <div className="mt-0.5 text-xs text-[#64748b] dark:text-[#94a3b8]">
                    {swatch.role}
                  </div>
                </div>
              </motion.button>
            );
          })}
        </div>
      </section>
    </div>
  );
}
