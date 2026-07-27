import type { ReactElement, SVGProps } from "react";

type IconProps = SVGProps<SVGSVGElement>;

function baseProps(props: IconProps): IconProps {
  return {
    width: 16,
    height: 16,
    viewBox: "0 0 16 16",
    fill: "none",
    stroke: "currentColor",
    strokeWidth: 1.5,
    strokeLinecap: "round",
    strokeLinejoin: "round",
    "aria-hidden": true,
    ...props,
  };
}

export function AndroidIcon(props: IconProps): ReactElement {
  return (
    <svg width={16} height={16} viewBox="0 0 16 16" aria-hidden {...props}>
      <g fill="#3DDC84" stroke="none">
        <path d="M3.5 9a4.5 4.5 0 0 1 9 0v3h-9z" />
        <rect x="1.5" y="9" width="1.7" height="3.6" rx="0.85" />
        <rect x="12.8" y="9" width="1.7" height="3.6" rx="0.85" />
        <rect x="4.9" y="12" width="1.9" height="2.6" rx="0.95" />
        <rect x="9.2" y="12" width="1.9" height="2.6" rx="0.95" />
        <line x1="5.4" y1="4.6" x2="4.3" y2="2" stroke="#3DDC84" strokeWidth="1" strokeLinecap="round" />
        <line x1="10.6" y1="4.6" x2="11.7" y2="2" stroke="#3DDC84" strokeWidth="1" strokeLinecap="round" />
      </g>
      <circle cx="6" cy="7.6" r="0.6" fill="#ffffff" />
      <circle cx="10" cy="7.6" r="0.6" fill="#ffffff" />
    </svg>
  );
}

export function IosIcon(props: IconProps): ReactElement {
  return (
    <svg width={16} height={16} viewBox="0 0 16 16" fill="#1a1a1a" aria-hidden {...props}>
      <path d="M10.62 2.02c.04.83-.28 1.62-.86 2.2-.59.58-1.4.91-2.2.87-.05-.82.3-1.62.87-2.19.6-.6 1.42-.94 2.19-.88zM12.4 9.28c-.34.79-.5 1.15-.95 1.85-.62.99-1.49 2.22-2.57 2.23-.96.01-1.21-.63-2.51-.62-1.31.01-1.58.63-2.54.62-1.08-.01-1.9-1.12-2.53-2.11-1.4-2.13-1.66-4.36-.66-5.65.72-1.03 1.85-1.64 2.91-1.64 1.08 0 1.76.63 2.66.63.87 0 1.4-.63 2.65-.63.95 0 1.95.52 2.67 1.41-2.35 1.29-1.97 4.62.28 5.51z" />
    </svg>
  );
}

export function PcIcon(props: IconProps): ReactElement {
  return (
    <svg {...baseProps(props)}>
      <rect x="1.5" y="2.5" width="13" height="8.5" rx="1.5" />
      <line x1="6" y1="14" x2="10" y2="14" />
      <line x1="8" y1="11" x2="8" y2="14" />
    </svg>
  );
}

export function WebIcon(props: IconProps): ReactElement {
  return (
    <svg {...baseProps(props)}>
      <circle cx="8" cy="8" r="6.5" />
      <ellipse cx="8" cy="8" rx="2.75" ry="6.5" />
      <line x1="1.5" y1="8" x2="14.5" y2="8" />
    </svg>
  );
}

export function SparkleIcon(props: IconProps): ReactElement {
  return (
    <svg width={16} height={16} viewBox="0 0 16 16" aria-hidden {...props}>
      <defs>
        <linearGradient id="ops-sparkle-gradient" x1="0" y1="0" x2="16" y2="16">
          <stop offset="0%" stopColor="#8AB4FF" />
          <stop offset="50%" stopColor="#E2A6FF" />
          <stop offset="100%" stopColor="#FFB199" />
        </linearGradient>
      </defs>
      <path
        fill="#ffffff"
        opacity="0.55"
        transform="translate(8 8) scale(1.35) translate(-8 -8)"
        d="M8 0c0 4.05 3.95 8 8 8-4.05 0-8 3.95-8 8 0-4.05-3.95-8-8-8 4.05 0 8-3.95 8-8z"
      />
      <path
        fill="url(#ops-sparkle-gradient)"
        d="M8 0c0 4.05 3.95 8 8 8-4.05 0-8 3.95-8 8 0-4.05-3.95-8-8-8 4.05 0 8-3.95 8-8z"
      />
    </svg>
  );
}
