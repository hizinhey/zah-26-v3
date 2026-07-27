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
    <svg {...baseProps(props)}>
      <rect x="3.5" y="5.5" width="9" height="7" rx="2" />
      <line x1="3.5" y1="8.5" x2="1.5" y2="8.5" />
      <line x1="14.5" y1="8.5" x2="12.5" y2="8.5" />
      <line x1="5.5" y1="3" x2="4.5" y2="1.5" />
      <line x1="10.5" y1="3" x2="11.5" y2="1.5" />
      <line x1="6" y1="8.5" x2="6" y2="8.5" />
      <line x1="10" y1="8.5" x2="10" y2="8.5" />
    </svg>
  );
}

export function IosIcon(props: IconProps): ReactElement {
  return (
    <svg {...baseProps(props)}>
      <rect x="5" y="1.5" width="6" height="13" rx="1.5" />
      <line x1="7" y1="12" x2="9" y2="12" />
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
    <svg {...baseProps({ fill: "currentColor", stroke: "none", ...props })}>
      <path d="M8 1.5c0.3 2.3 1 3.9 2 4.9 1 1 2.6 1.7 4.9 2-2.3 0.3-3.9 1-4.9 2-1 1-1.7 2.6-2 4.9-0.3-2.3-1-3.9-2-4.9-1-1-2.6-1.7-4.9-2 2.3-0.3 3.9-1 4.9-2 1-1 1.7-2.6 2-4.9z" />
    </svg>
  );
}
